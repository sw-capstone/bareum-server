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
- `.env.deploy`의 `AUTH_ENV_ENABLED`가 없거나 `false`여도 #18의 DB·이메일 설정 9개와 SMTP·발송 정책 템플릿을 유지한다. 전체 Auth·Term 기능이 포함된 이미지를 배포하기 전에 추가 설정을 준비하고 `true`로 변경한다.
- 환경 파일 생성 스크립트는 `AUTH_ENV_ENABLED`를 파일에서 읽으며 셸에서 `source`하지 않는다. 단독 생성 명령의 `--with-auth`도 같은 검증을 사용한다. 생성만으로 실행 중인 컨테이너에 설정이 반영되지 않는다.
- `true`이면 JWT·Origin·확인 정책 등 추가 Auth·Term 설정을 함께 조회한다. 필요한 값이 없거나 잘못되면 실패하며 기존 `.env.server`를 교체하지 않는다. `false` 모드는 메일 발송 준비만 유지하며 전체 인증 기능의 설정 완료를 의미하지 않는다.
- 값을 출력하지 않고, 권한을 제한한 임시 파일에서 검증한 뒤 `.env.server`를 권한 600으로 교체한다. AWS·JSON 오류의 원문은 배포 로그에 출력하지 않는다.
- 허용한 파라미터 이름만 최대 10개씩 조회한다. 경로 아래의 모든 값을 읽지 않으며, 템플릿에 중복된 관리 대상 키는 제거한다. `raw` 환경 파일이므로 값의 `$`, 따옴표, `=`를 셸에서 실행하거나 치환하지 않는다. 개행·NUL·빈 값은 거부한다.

## 파라미터 목록

### 배포 단계별 준비

| 단계 | 유지·추가할 설정 | 준비 기준 |
| --- | --- | --- |
| #18 메일 발송 | 기존 v2의 29개 값. SSM 기본 조회는 DB·메일 9개, 나머지는 템플릿 | 기존 DB·두 인증키·SMTP 자격증명을 재사용한다. 전체 인증 비활성화로 메일 설정을 제거하지 않는다. |
| #57 인증번호 확인 | `EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS`, 재설정 확인 시 별도 완료 증명 기간 | v2만으로는 503이다. 5회 제한만 넣어 복구까지 적용됐다고 보지 않으며 #54의 제한·복구 코드와 함께 사용자 연동을 준비한다. |
| 전체 이메일 인증 | `LOGIN_JWT_SIGNING_KEY`, `LOGIN_ALLOWED_ORIGINS` 및 확인 정책 | JWT 키는 메일·Outbox와 독립된 키를 준비한다. Origin은 정확한 주소를 사용한다. 약관은 DB 데이터를 연결하며 별도 버전 환경변수는 사용하지 않는다. |
| Google 인증 | `GOOGLE_CLIENT_IDS`, Console 승인 Origin·프론트 SDK | 실제 승인된 Client ID를 사용한다. 누락 시 Google 인증을 차단하며 가짜 값을 사용하지 않는다. |
| #54 복구 | 실패 5회·대기 900초·재설정 완료 증명 600초 | 선택된 임시 기준이다. 해당 코드·migration·설정과 함께 반영하며 팀의 최종 승인·실제 배포와 구분한다. |

v2의 `AUTH_EMAILVERIFICATION_*` 직접 바인딩 이름은 메일 전용 모드에서 유지한다. 전체 인증 모드의 SSM에 대응하는 `EMAIL_VERIFICATION_*` 값이 있으면 해당 기존 별칭을 생성 파일에서 제거해 한 속성에 서로 다른 값이 함께 남지 않게 한다. 별도 설정이 없는 항목은 기존 템플릿을 유지한다. v2 원본이나 실행 중인 AWS 설정을 이 절차만으로 수정하지 않는다.

이름 앞에 `/bareum/dev/server/`를 붙인다. 아래 필수 설정은 활성화 전에 담당자가 승인된 값으로 준비한다. 수치·발신 계정을 이 문서에서 정하지 않는다.

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
| `EMAIL_VERIFICATION_MINIMUM_SEND_INTERVAL_SECONDS` | String · Auth 필수 | 개발 서버 v2 임시안 60초. 팀 확인·실제 환경 적용 필요 |
| `EMAIL_VERIFICATION_MAX_SENDS_PER_HOUR`, `EMAIL_VERIFICATION_MAX_SENDS_PER_DAY` | String · Auth 필수 | 개발 서버 v2 임시안 최근 1시간 5회·24시간 20회. 팀 확인 필요 |
| `EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS` | String · Auth 필수 | 승인된 양의 인증 실패 제한 |
| `GOOGLE_CLIENT_IDS` | String · 선택 | 승인된 웹 OAuth Client ID 목록. 미설정 시 Google 로그인 차단 |
| `GOOGLE_SIGNUP_TTL_SECONDS` | String · 선택 | 기존 구현의 1~600초 범위. 미설정 시 기존 600초 초안 유지 |
| `EMAIL_VERIFICATION_PASSWORD_RESET_CODE_TTL_SECONDS`, `EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS` | String · 선택 | 번호 임시안은 300초, 완료 증명 기간은 별도 미정. 재설정 활성화 시 두 값을 함께 준비 |

SMTP 제공자에 따라 인증 SMTP와 implicit TLS·필수 STARTTLS 중 하나를 선택한다. 서버 이름 검증은 켜고 기존 연결·읽기·쓰기 5초 기본 타임아웃을 유지한다. 제공자가 다른 인증 방식을 요구하면 자격증명을 우회하지 않고 주입 경로와 검증 기준을 재검토한다.

번호 300초·발송 간격 60초·시간당 5회·일일 20회는 개발 서버 v2의 임시 선택이며 운영 기본값이나 팀 확정 정책이 아니다. ‘재발송 5분’은 재발송 번호의 유효기간으로 우선 해석했으며 팀 확인이 필요하다. #18 v2의 `AUTH_EMAILVERIFICATION_*` 이름은 직접 바인딩용이며, 이 문서의 후속 SSM 주입 경로는 위 `EMAIL_VERIFICATION_*` 이름을 사용한다. 같은 속성의 두 이름을 서로 다른 값으로 함께 등록하지 않는다. [이메일 인증 임시 정책](../decisions/email-verification-draft.md)을 따른다.

Access 15분·세션 최대 8시간, Secure·SameSite=None 쿠키, Outbox 재시도·대기 기준 등 기존 구현값을 바꾸지 않는다. 비밀번호 재설정 기간의 설정은 MVP 배포 승인을 의미하지 않는다.

## 적용 전후 확인

1. 관련 인증 PR의 리뷰·병합과 배포할 이미지의 범위를 확인한다.
2. 운영 발신 계정·발송 제한을 확인하고 약관 DB의 네 항목·필수 여부·버전을 준비한다. 개발 중 임시 원문은 허용하며 실제 가입 적용 전 표시 원문과 기록할 DB 버전을 맞춘다.
3. JWT 서명 키를 포함한 파라미터와 조회·복호화 권한을 준비한다. JWT 전환 후 기존 불투명 로그인 토큰은 사용할 수 없으므로 재로그인 안내를 반영한다. 여러 조회 배치가 하나의 스냅샷은 아니므로 설정 변경을 마친 뒤 배포하며, 키 변경 중에는 배포를 실행하지 않는다.
4. `.env.deploy`에서 `AUTH_ENV_ENABLED=true`를 지정하고 기존 배포 경로로 적용한다. 새 설정 생성 실패 시 기존 환경은 유지되며 배포는 실패로 보고된다.
5. 배포 이미지·DB migration·공개 OpenAPI 경로를 확인하고 이메일 발송·상태 조회·가입·로그인·갱신·로그아웃을 검증한다. `/health` 성공만으로 인증 기능 준비를 판정하지 않는다.

실제 Parameter Store·EC2 적용, 운영 SMTP 수신, 승인 약관 DB와 실제 Google 계정·브라우저 연동은 별도 환경 검증 대상이다. 배포 실패 시 기존 이미지·환경 복구는 기존 배포 스크립트를 따르며 DB migration은 자동으로 되돌아가지 않는다.

## 임시 구현 기준과 팀 협의 사항

아래는 기존 명세에 구체적인 기준이 없어 구현을 위해 임의로 선택한 내용이다. 팀 확정 정책이 아니며, 승인 시 설정·문서·검증을 함께 갱신한다. 상세 쟁점·선택지·영향·재검토 조건은 [배포 협의 문서](../decisions/auth-term-deployment-draft.md)에 기록한다.

| 항목 | 임시 구현 기준 | 영향·팀 확인 및 재검토 조건 |
| --- | --- | --- |
| 적용 시점 | 누락·false도 #18의 DB·메일 설정 9개와 템플릿을 유지하고 true는 추가 Auth·Term 설정도 조회 | 전체 인증 이미지 배포 전에 추가 값을 준비한다. 메일 키를 누락시키거나 기존 키를 새로 만들지 않는다. |
| 설정 관리·필수 범위 | 기존 Parameter Store 경로에서 허용한 이름만 조회. 이메일 가입·로그인의 핵심 설정은 함께 필수로 검증하고 Google·재설정 기간은 선택 | 수동으로 추가한 관리 대상 값은 다음 생성 때 보존하지 않는다. 기능별 활성화·설정 소유 기준을 정할 때 재검토한다. |
| 비밀값·파일 보호 | DB 비밀번호·JWT 서명 키·HMAC 키·Outbox 키·SMTP 비밀번호는 SecureString. 검증 후 환경 파일을 권한 600으로 교체 | EC2 조회·KMS 복호화 권한, 키 교체와 환경 백업 보관 기준은 인프라 담당자와 확인한다. |
| SMTP 연결 | 인증 SMTP와 implicit TLS 또는 필수 STARTTLS만 허용. 서버 이름 검증, 연결·읽기·쓰기 기본 5초 | 제공자와 인증 방식·허용 지연을 확정할 때 재검토한다. 실제 운영 발신 계정은 선택하지 않았다. |
| 설정 조회 일관성 | 이름을 최대 10개씩 조회하고 전체 검증 후 파일 교체. 조회 배치 전체의 스냅샷은 보장하지 않음 | 설정 변경을 마친 뒤 배포한다. 키 교체·동시 설정 변경을 운영할 때 버전 묶음 방식을 검토한다. |
| 약관 초기 데이터 | 네 boolean 동의를 DB 약관에 연결. 별도 버전 환경변수와 버전 비교 503 제거 | 원문은 프론트에서 관리하며 개발 중 임시 원문 허용. 실제 사용자 적용 전 표시 원문과 DB 버전을 맞춘다. |

### 승인된 실제 값 준비와 배포 적용이 필요한 항목

아래 항목은 임시 운영 값을 지정했다는 의미가 아니다. 설정 주입 코드는 준비됐으나 승인된 실제 값은 준비가 필요하고, Parameter Store·권한·EC2 적용은 확인되지 않았다.

| 항목 | 구현 상태 | 남은 결정·적용 |
| --- | --- | --- |
| 운영 SMTP 제공자·발신 계정 | SMTP 인증·TLS·발신 주소 설정 경로 구현 | 운영 서비스·발신 계정 결정 및 자격증명 적용. 개발 Gmail 수신 검증을 운영 설정 완료로 취급하지 않음 |
| 사용자별 메일 발송·인증 제한 | 발송 간격·시간당/일당 횟수·인증 실패 횟수 설정 및 누락 시 차단 구현 | 발송 임시안은 60초·최근 1시간 5회·24시간 20회. 팀 확인·환경 주입 필요. 인증 실패 횟수·복구는 별도 미정 |
| 약관 동의 저장 | 필수 3개 검증·선택 응답 저장·DB 버전 연결 | 개발용 네 약관 데이터를 준비하고 실제 사용자 적용 전 원문·DB 버전 대응 확인 |
| 허용 Origin·Google Client ID | 정확한 HTTPS Origin 제한·Client ID 검증 설정 경로 구현 | 미리보기 주소는 알려져 있으나 허용 대상 승인·환경 등록은 미확인. 실제 웹 Client ID와 Console 승인 Origin 준비 |
| 비밀번호 찾기 | 발송·인증·재설정 구현 및 개발 메일 검증 완료 | MVP 제외 기준 유지. 번호 임시안 300초의 팀 확인, 완료 증명 기간·배포 포함 여부는 별도 결정 필요 |

Access 15분·최대 세션 8시간은 합의된 구현 기준이며, 회원가입 인증번호 5분·인증 완료 후 가입 10분은 최신 정책 기준이다. 쿠키 SameSite·경로와 Outbox 재시도·대기 기본값은 임시 기준으로 이미 구현되어 있고, 위 실제 값 미지정 항목과 구분한다. Google 지원 계정 범위와 키 교체·실패 복구 기준은 기존 구현의 재검토 사항으로 남긴다.
