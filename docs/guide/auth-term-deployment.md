# Auth·Term 배포 설정 준비

Auth·Term의 서버 설정을 기존 개발 EC2 배포 경로에 연결한다. 코드·설정 준비와 운영 정책 승인·실제 환경 적용은 구분한다. [배포 구성](deployment-guide.md)과 [Auth·Term 연동 안내](../integration/auth-term.md)를 함께 따른다.

## 구현·설정 상태 구분

정책의 확정 여부, 코드 구현 여부, 배포 환경 적용 여부를 별도로 판단한다.

| 구분 | 의미 |
| --- | --- |
| 명세·합의 기준으로 구현 | 최신 정책 또는 담당자 간 합의에 따라 코드에 반영한 기준. 실제 배포·브라우저 연동 완료를 뜻하지 않음 |
| 임시 기준으로 구현·팀 확인 필요 | 코드에 현재 동작과 임시 기본값이 있음. 팀 확정 정책이 아니며 변경 시 구현·검증도 함께 수정 |
| 설정 경로 구현·실제 값 준비 필요 | 값을 받는 코드와 배포 주입 경로는 있음. 승인된 운영 값은 아직 정하지 않았으며 배포 적용도 확인하지 않음 |
| 프론트 처리 기준 미정 | API는 구현됐지만 상태 조회 간격·최대 대기 시간·응답 유실 복구 등 소비자 동작을 추가로 맞춰야 함 |

실제 Parameter Store 값·권한·EC2 적용은 별도 확인 대상이다. 미정 값에 테스트 설정을 운영 기본값으로 적용하지 않는다.

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
| `LOGIN_JWT_SIGNING_KEY` | SecureString · Auth 필수 | 최소 32바이트 독립 난수의 Base64 서명 키. Google·메일·Outbox 키와 재사용하지 않음 |
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
3. JWT 서명 키를 포함한 파라미터와 조회·복호화 권한을 준비한다. JWT 전환 후 기존 불투명 로그인 토큰은 사용할 수 없으므로 재로그인 안내를 반영한다. 여러 조회 배치가 하나의 스냅샷은 아니므로 설정 변경을 마친 뒤 배포하며, 키 변경 중에는 배포를 실행하지 않는다.
4. `.env.deploy`에서 `AUTH_ENV_ENABLED=true`를 지정하고 기존 배포 경로로 적용한다. 새 설정 생성 실패 시 기존 환경은 유지되며 배포는 실패로 보고된다.
5. 배포 이미지·DB migration·공개 OpenAPI 경로를 확인하고 이메일 발송·상태 조회·가입·로그인·갱신·로그아웃을 검증한다. `/health` 성공만으로 인증 기능 준비를 판정하지 않는다.

실제 Parameter Store·EC2 적용, 운영 SMTP 수신, 승인 약관 DB와 실제 Google 계정·브라우저 연동은 별도 환경 검증 대상이다. 배포 실패 시 기존 이미지·환경 복구는 기존 배포 스크립트를 따르며 DB migration은 자동으로 되돌아가지 않는다.

## 임시 구현 기준과 팀 협의 사항

아래는 기존 명세에 구체적인 기준이 없어 구현을 위해 임의로 선택한 내용이다. 팀 확정 정책이 아니며, 승인 시 설정·문서·검증을 함께 갱신한다. 상세 쟁점·선택지·영향·재검토 조건은 [배포 협의 문서](../decisions/auth-term-deployment-draft.md)에 기록한다.

| 항목 | 임시 구현 기준 | 영향·팀 확인 및 재검토 조건 |
| --- | --- | --- |
| 적용 시점 | `AUTH_ENV_ENABLED` 누락·false는 DB만 조회하고 true는 Auth·Term 설정도 조회 | 인증 이미지 배포 전에 필수 값을 준비하고 활성화해야 한다. 단계별 배포 범위를 확정할 때 재검토한다. |
| 설정 관리·필수 범위 | 기존 Parameter Store 경로에서 허용한 이름만 조회. 이메일 가입·로그인의 핵심 설정은 함께 필수로 검증하고 Google·재설정 기간은 선택 | 수동으로 추가한 관리 대상 값은 다음 생성 때 보존하지 않는다. 기능별 활성화·설정 소유 기준을 정할 때 재검토한다. |
| 비밀값·파일 보호 | DB 비밀번호·JWT 서명 키·HMAC 키·Outbox 키·SMTP 비밀번호는 SecureString. 검증 후 환경 파일을 권한 600으로 교체 | EC2 조회·KMS 복호화 권한, 키 교체와 환경 백업 보관 기준은 인프라 담당자와 확인한다. |
| SMTP 연결 | 인증 SMTP와 implicit TLS 또는 필수 STARTTLS만 허용. 서버 이름 검증, 연결·읽기·쓰기 기본 5초 | 제공자와 인증 방식·허용 지연을 확정할 때 재검토한다. 실제 운영 발신 계정은 선택하지 않았다. |
| 설정 조회 일관성 | 이름을 최대 10개씩 조회하고 전체 검증 후 파일 교체. 조회 배치 전체의 스냅샷은 보장하지 않음 | 설정 변경을 마친 뒤 배포한다. 키 교체·동시 설정 변경을 운영할 때 버전 묶음 방식을 검토한다. |
| 약관 초기 데이터 | 환경변수로 승인 버전만 연결하고 미승인 원문·버전·DB 데이터를 생성하지 않음 | 네 약관의 공식 원문·버전·필수 여부 승인 후 DB와 프론트 원문을 맞춘다. |

### 승인된 실제 값 준비와 배포 적용이 필요한 항목

아래 항목은 임시 운영 값을 지정했다는 의미가 아니다. 설정 주입 코드는 준비됐으나 승인된 실제 값은 준비가 필요하고, Parameter Store·권한·EC2 적용은 확인되지 않았다.

| 항목 | 구현 상태 | 남은 결정·적용 |
| --- | --- | --- |
| 운영 SMTP 제공자·발신 계정 | SMTP 인증·TLS·발신 주소 설정 경로 구현 | 운영 서비스·발신 계정 결정 및 자격증명 적용. 개발 Gmail 수신 검증을 운영 설정 완료로 취급하지 않음 |
| 사용자별 메일 발송·인증 제한 | 발송 간격·시간당/일당 횟수·인증 실패 횟수 설정 및 누락 시 차단 구현 | 운영 수치는 미지정. Outbox worker의 임시 재시도 기본값과 별개이며 실패 횟수 복구 정책도 협의 필요 |
| 공식 약관 원문·승인 버전 | 네 boolean 동의 처리·서버 버전 매핑·DB 일치 검증 구현 | 공식 원문·버전·필수 여부 승인 후 프론트 원문·서버 설정·DB 데이터 일치 확인 |
| 허용 Origin·Google Client ID | 정확한 HTTPS Origin 제한·Client ID 검증 설정 경로 구현 | 미리보기 주소는 알려져 있으나 허용 대상 승인·환경 등록은 미확인. 실제 웹 Client ID와 Console 승인 Origin 준비 |
| 비밀번호 찾기 | 발송·인증·재설정 구현 및 개발 메일 검증 완료 | MVP 제외 기준 유지. 배포 포함 여부·재설정용 두 유효기간은 미정이며 설정값 준비 필요 |

Access 15분·최대 세션 8시간은 합의된 구현 기준이며, 회원가입 인증번호 5분·인증 완료 후 가입 10분은 최신 정책 기준이다. 쿠키 SameSite·경로와 Outbox 재시도·대기 기본값은 임시 기준으로 이미 구현되어 있고, 위 실제 값 미지정 항목과 구분한다. Google 지원 계정 범위와 키 교체·실패 복구 기준은 기존 구현의 재검토 사항으로 남긴다.
