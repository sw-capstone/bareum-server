# Google 로그인 구현안 및 협의 사항

관련 작업: AUTH-004 · BRM-29 · GitHub #26. 신규 가입 완료는 AUTH-011 · BRM-33 · #30에서 구현한다.

## 기준과 구현 범위

- [AUTH-004 최신 명세](https://app.notion.com/p/57011880adea8339956d010fe1c43f17): Google 인증을 검증하고 기존 회원 로그인과 신규 회원의 가입용 임시 증명을 구분한다.
- [AUTH-011 최신 명세](https://app.notion.com/p/24511880adea833e8870018de01d7df3): 임시 증명·필수 약관 검증 후 회원·Google 인증 연결·동의를 함께 저장하고 자동 로그인한다.
- [기술·정책 기준](https://app.notion.com/p/3e311880adea8077a667c9a8eae3d107): 동일 이메일의 가입 방식 간 중복과 자동 계정 연결을 허용하지 않는다. Google 프로필은 이메일·이름만 사용한다.
- 기존 회원은 Google의 `sub`와 member_auth 연결로 식별한다. 이메일 변경으로 다른 계정에 연결하거나 저장된 회원 이메일·이름을 자동 변경하지 않는다. 기존 약관 동의도 다시 저장하지 않는다.
- ACTIVE·미탈퇴·GOOGLE 회원이며 인증 연결이 해제되지 않았는지 DB 트랜잭션에서 재확인한다. Google 공개키 조회는 DB 트랜잭션 밖에서 수행한다.
- 로그인 토큰과 갱신 쿠키는 [공통 로그인 기준](login-authentication-draft.md)을 재사용한다. /me·토큰 갱신·로그아웃에 같은 세션을 사용한다.
- 신규 회원에게는 가입용 증명만 반환한다. 이 API에서 회원·인증 연결·약관 동의를 저장하거나 로그인 토큰을 발급하지 않는다.

## API 연동 초안

아래 전달 형식과 신규 오류 코드는 팀·프론트의 확정 계약이 아닌 구현 초안이다.

`POST /api/v1/auth/login/google` · `Content-Type: application/json`

1. Google Identity Services의 JavaScript callback에서 받은 ID Token을 사용한다. Google Access Token·인증 코드·클라이언트가 지정한 이메일·이름으로 인증하지 않는다.
2. `GET /api/v1/auth/csrf` 응답의 token을 `X-CSRF-TOKEN` 헤더로 보내고, 조회 시 설정된 XSRF-TOKEN 쿠키도 함께 보낸다. 쿠키 전달을 위해 credentials를 활성화한다.
3. 요청 본문: `{"idToken":"<Google ID Token>"}`. 필수이며 최대 8192자다.

기존 회원 성공 HTTP 200:

```json
{"status":"LOGIN","accessToken":"<accessToken>","tokenType":"Bearer","expiresIn":900,"sessionExpiresIn":28800}
```

Refresh Token은 본문에 포함하지 않고 bareum_refresh HttpOnly·Secure 쿠키로 반환한다. 사용자 정보는 `GET /api/v1/auth/me`에서 조회한다.

신규 회원 성공 HTTP 200:

```json
{"status":"SIGNUP_REQUIRED","signupToken":"<signupToken>","signupExpiresIn":600}
```

가입용 증명은 AUTH-011에만 전달하며 브라우저 영구 저장소에 보관하지 않는다. 보호 API의 Bearer 인증과 토큰 갱신에 사용할 수 없다. 성공 응답에는 Cache-Control: no-store·Pragma: no-cache를 적용한다.

| 코드 | HTTP | 의미 |
|---|---|---|
| 기존 COMMON 코드 | 400 | 필수 입력·길이·JSON 검증 실패 |
| AUTH-0024 | 401 | Google 서명·발급자·대상 Client ID·만료·필수 프로필 검증 실패 또는 다른 Google 식별자의 이메일 충돌 |
| AUTH-0016 | 403 | 제한 회원·탈퇴·인증 연결 해제·가입 방식 불일치 |
| AUTH-0018 | 403 | CSRF 검증 실패 |
| AUTH-0028 | 403 | 신규 가입 이메일의 추가 소유권 확인 필요 |
| AUTH-0027 | 409 | 대소문자를 무시했을 때 동일 이메일의 LOCAL 계정 존재 |
| AUTH-0020 | 429 | 공통 로그인 요청 제한 초과 |
| AUTH-0025 | 503 | Google Client ID 미설정 |
| AUTH-0026 | 503 | Google 공개키 조회 장애 |
| AUTH-0019 | 503 | 인증 저장소·트랜잭션 장애 |

오류는 기존 ProblemDetail을 사용한다. HTTP 400 문서는 BadRequestProblemDetail의 anyOf를 참조한다. 토큰·프로필 원문과 외부 검증·DB 오류의 상세 내용을 오류 응답에 포함하지 않는다.

## 보안·데이터 경계

- Spring Security의 NimbusJwtDecoder로 RS256 서명을 검증한다. 공개키 URL은 Google HTTPS 주소로 고정하고, ID Token 헤더의 jku·jwk로 키를 선택하지 않는다. 공개키 캐시와 키 교체 조회는 라이브러리를 사용한다. 연결·읽기 대기는 각각 3초로 제한한다.
- iss는 Google의 두 공식 발급자 값만 허용한다. aud는 설정된 Client ID와 일치해야 한다. azp가 있으면 허용한 Client ID여야 하며, 여러 aud가 있으면 azp도 요구한다. exp·iat·선택적 nbf, sub·email·email_verified와 이름·도메인의 타입·길이를 검증한다.
- Google 식별자별 advisory lock을 사용한다. 기존 회원은 회원→인증 연결→세션 순서로 확인·저장하며, 신규 이메일 충돌은 기존 upper(email) 비교와 이메일 잠금 기준을 사용한다.
- 가입용 증명은 gs_ 접두사와 SecureRandom 32바이트 난수로 구성한다. 용도를 구분한 SHA-256 해시만 google_signup_proof에 저장하며 Google ID Token 원문은 저장하지 않는다.
- 같은 sub의 재요청은 이전 가입용 증명을 교체한다. 교체와 저장은 같은 트랜잭션에서 처리한다. 만료된 증명은 매시간 정리한다.
- AUTH-011은 증명 해시·만료·미사용 상태를 잠그고 확인한 뒤 최종 이메일 중복 확인, 회원·연결·약관 동의 저장과 consumed_at 기록을 함께 커밋해야 한다. 증명 소비와 Google 가입 완료는 이번 구현에 포함되지 않는다.

## 임시 선택·협의 필요 사항

아래 선택은 개발에 필요한 임시 기준이며 팀에서 확정한 정책으로 취급하지 않는다.

| 쟁점 | 임시 구현 | 선택지·영향 | 재검토 조건 |
|---|---|---|---|
| Google 전달 방식 | ID Token JSON 요청과 기존 CSRF 쿠키·헤더 사용 | Google redirect/form POST 또는 서버 Authorization Code 방식은 callback·state·nonce·쿠키 계약을 별도로 맞춰야 한다. 현재 해당 방식은 지원하지 않음 | 프론트 Google SDK·인증 전달 방식 확정 전 |
| 앱 설정 | GOOGLE_CLIENT_IDS의 명시된 Client ID만 허용. 누락 시 503으로 차단 | 운영·Preview 클라이언트를 구분할지 결정하고 Google Console의 승인 Origin과 서버 CORS를 함께 등록해야 함 | 운영·Preview 도메인 및 Google 프로젝트 결정 시 |
| 가입용 증명 | 최대 10분, 해시 저장, 동일 sub의 최신 증명만 유지 | 기간 단축·연장이나 여러 동시 가입 화면을 허용하면 UX·증명 저장 기준 변경 필요 | AUTH-011·프론트 약관 흐름 연결 전 |
| 신규 이메일 소유권 | email_verified=true와 함께 Gmail 또는 Workspace의 hd를 요구. 그 외 신규 이메일은 AUTH-0028로 차단 | Google은 제3자 메일의 현재 소유권까지 보장하지 않으므로 별도 이메일 인증 흐름을 연결하거나 신규 가입 허용 범위를 결정해야 함. 기존 회원은 검증된 sub로 로그인 가능 | 지원할 Google 계정 범위와 추가 인증 UX 결정 전 |
| 제한·탈퇴 연결 | 기존 Google 인증 연결의 회원이 제한·탈퇴·연결 해제 상태이면 로그인 거부 | 탈퇴 Google 계정의 재가입은 기존 sub 연결을 재사용·해제·새 계정에 연결할지 별도 기준 필요. 다른 sub의 동일 이메일은 DELETED와 탈퇴 시각이 모두 있는 계정만 충돌 검사에서 제외 | 탈퇴·Google 재가입 정책 및 AUTH-011 구현 전 |
| 요청 제한 | 검증된 이메일에 공통 로그인 요청 제한 적용 | 미검증 토큰의 공개키 조회 비용과 여러 이메일의 분산 요청은 게이트웨이의 전역·IP 제한이 추가로 필요. 현재 이메일 제한만으로 전체 남용을 막지 않음 | 배포·남용 방지 기준 확정 전 |
| 프로필 저장·정리 | 가입 대기 중 sub·이메일·이름을 저장하며 만료 후 최대 1시간 이내 정리 | 가입 완료 이력 보존과 로그 정책은 회원·개인정보 기준과 함께 정해야 함 | 운영 보존·삭제 정책 결정 시 |
| 공개 응답·오류 | LOGIN/SIGNUP_REQUIRED와 AUTH-0024~0028 사용 | 프론트 현재 Mock 계약에는 이 분기가 없어 화면·서비스·오류 처리 검토 필요 | 프론트 계약 합의·실제 연동 전 |

## 설정

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| GOOGLE_CLIENT_IDS | 비어 있음 | 허용할 Google 웹 OAuth Client ID의 쉼표 구분 목록. 서버는 Client Secret을 사용하지 않음 |
| GOOGLE_SIGNUP_TTL_SECONDS | 600 | 가입용 증명 유효기간. 1~600초 |
| LOGIN_ALLOWED_ORIGINS | 비어 있음 | 기존 로그인 CORS 기준의 정확한 프론트 Origin |

설정 예시는 [환경변수 예시](../../env/local/.env.example)에 포함한다. Google Console의 프로젝트·실제 Client ID·승인된 Origin은 별도로 준비해야 한다. 이 설정만으로 Google 동의 화면이나 프론트 SDK가 구성되지는 않는다.

## 검증과 남은 범위

- Google ID Token의 서명·발급자·대상·만료·프로필 검증, 공개키 캐시·교체·조회 실패와 공격자 키 주소 차단을 검증했다.
- 실제 HTTP 서버에서 두 성공 응답, HttpOnly 쿠키·본문 원문 제외, CSRF·CORS, 입력·업무 오류, 가입용 증명의 보호 API 차단과 OpenAPI의 예시·400 anyOf를 검증했다.
- 설정 불변성·유효기간 범위, 검증 실패 및 요청 제한 후 계정 저장 차단, 저장소 장애의 안전한 오류 변환을 검증했다.
- `./gradlew check bootJar --no-daemon`: 전체 504개 통과, 실패·오류·건너뜀 0개 및 JAR 빌드 성공. Google 관련 83개를 포함한다.
- PostgreSQL 통합 테스트로 Flyway 적용, 로그인·/me·갱신·로그아웃 연결, 계정 상태·대소문자 충돌, 증명 저장·교체·만료, 동시 요청과 롤백을 검증했다. 탈퇴 상태와 탈퇴 시각이 모두 있는 계정만 신규 이메일 충돌 검사에서 제외되는 경계도 확인했다.
- 하네스 정적 검사 4개 통과, 해당 없음 3개. 원격 CI 결과는 별도로 확인한다.
- 인증 설정을 적용한 HTTP 서버에서도 GET /health가 200·상태 응답을 반환하는지 검증했다.
- 실제 Google 프로젝트·계정과 프론트 SDK 연결 검증은 미실행이다. mock 서비스·생성한 서명 토큰 검증과 실제 Google 로그인 성공을 구분한다.
- 선행 로그인·토큰 갱신·로그아웃 PR이 미병합이므로 develop 비교에는 해당 변경도 포함된다. 선행 PR 병합 후 변경 범위와 회귀 검증을 다시 확인한다.
- 이메일 대소문자 DB 중복 제약·탈퇴 이메일 재사용은 #18의 변경과 연결된다. AUTH-011 병합 전 최신 기반에서 같은 비교·제약 기준과 migration 버전을 확인해야 한다.

## 참고

- [Google ID Token 서버 검증](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token)
- [Spring Security JWT 검증](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
