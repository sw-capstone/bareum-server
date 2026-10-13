# 이메일 로그인·공통 인증 구현 및 협의 사항

- 작성일: 2026-10-11
- 상태: 구현 초안·팀 검토 필요
- 소유 역할: 백엔드·프론트엔드
- 관련 작업: [Issue #24](https://github.com/sw-capstone/bareum-server/issues/24), BRM-27, AUTH-002

## 기준과 구현 범위

- [AUTH-002](https://app.notion.com/p/57011880adea83868d2801458ac96772)의 이메일·비밀번호 검증, Google 전용 계정과 제한 상태 구분을 적용한다.
- [기술·정책 명세](https://app.notion.com/p/3e311880adea8077a667c9a8eae3d107)의 가입 후 자동 로그인 없음, 이메일 가입·로그인 우선 구현, 비밀번호 찾기·로그인 상태 유지 후순위 조건을 따른다.
- 프론트와 협의한 Access Token 15분, 최초 로그인부터 최대 8시간, 메모리 보관, HttpOnly 갱신 쿠키, 사용자 정보 조회 API 제공 방향을 반영한다. 토큰 형식은 기존 서비스 명세의 JWT를 따르며, 회원당 단일 Refresh와 재로그인 시 이전 Access의 만료까지 허용은 #7 리뷰 기준을 따른다. 쿠키 세부 설정·오류 코드는 구현 기준으로 별도 검토한다.
- 이번 범위는 이메일 로그인, 토큰·쿠키 발급, Bearer 인증, 현재 사용자 조회다. 갱신 API는 BRM-30/AUTH-009, 로그아웃은 BRM-28, Google 로그인은 BRM-29에서 연결한다. 갱신 API·CSRF 전달 계약은 [토큰 갱신 협의 문서](token-renew-draft.md)에 별도로 정리한다.
- [PR #33](https://github.com/sw-capstone/bareum-server/pull/33)의 이메일 가입·고정 약관 동의 구현을 기반으로 한다. 선행 PR이 병합되면 최신 develop과 비교 범위를 정리하고 회귀 검사를 다시 확인한다. 이메일 중복 인덱스와 Outbox 등 [PR #18](https://github.com/sw-capstone/bareum-server/pull/18)의 후속 변경은 해당 PR 병합 뒤 반영해야 한다.

## 현재 API 계약

### 이메일 로그인

`POST /api/v1/auth/login/email`

```json
{
  "email": "member@example.invalid",
  "password": "<입력한 비밀번호>",
  "keepSignedIn": false
}
```

- email·password는 필수다. 이메일은 DB IgnoreCase 조회 기준을 사용하며, 대소문자만 다른 주소를 별도 계정으로 취급하지 않는다.
- 비밀번호는 공백 제거·대소문자 변환·절삭을 하지 않고 Argon2id 해시와 비교한다. 이전 PBKDF2 저장값은 읽기 호환만 제공하며, 새 가입·재설정 저장값은 Argon2id로 생성한다. 로그인에 신규 가입용 비밀번호 조건을 다시 적용하지 않는다. keepSignedIn은 생략·null·false만 허용하고 true는 400으로 거부한다.
- 성공은 HTTP 200이며 본문은 아래 네 필드다. 시간은 응답 시점의 남은 초를 반환하므로 예시보다 작을 수 있다.

```json
{
  "accessToken": "<서버가 발급한 JWT Access Token>",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "sessionExpiresIn": 28800
}
```

- Refresh Token은 본문에 포함하지 않고 `bareum_refresh` 쿠키로만 전달한다. 기본 속성은 HttpOnly·Secure·SameSite=None·Path=/api/v1/auth이며 Domain을 설정하지 않는다. Max-Age는 최초 로그인부터 8시간 이내의 남은 시간이다.
- 프론트는 로그인 요청에 `credentials: 'include'`를 사용해 쿠키를 받는다. Access Token은 메모리에서 관리하고 보호 API에 `Authorization: Bearer <accessToken>`을 붙인다.
- 로그인 응답에는 Cache-Control: no-store와 Pragma: no-cache를 적용한다. 실패 시 새 로그인 쿠키나 토큰을 발급하지 않는다.

### 현재 사용자 조회

`GET /api/v1/auth/me`

```http
Authorization: Bearer <accessToken>
```

성공 HTTP 200:

```json
{
  "memberId": 7,
  "name": "사용자",
  "email": "member@example.invalid"
}
```

- JWT 서명·발급자·수신 대상·용도·만료와 Access 해시 등록·폐기 여부, 회원 상태·탈퇴 시각을 확인한다. Refresh Token이나 쿠키만으로는 인증하지 않는다.
- 인증된 회원의 정보만 반환하며 Cache-Control: no-store를 적용한다. 다른 도메인의 보호 API는 이 회원 식별자를 이용해 리소스 소유권을 별도로 확인해야 한다.

### 오류 계약

필드 검증 오류와 업무 오류는 기존 ProblemDetail 계약을 사용한다. HTTP 400 문서는 BadRequestProblemDetail의 anyOf를 재사용한다.

| 코드 | HTTP | 의미 |
|---|---|---|
| 기존 COMMON 코드 | 400 | 잘못된 JSON, 필수 필드·이메일·지원하지 않는 keepSignedIn 값 |
| AUTH-0014 | 401 | 없는 계정 또는 비밀번호 불일치. 두 경우 같은 안내 |
| AUTH-0015 | 403 | 활성 Google 전용 계정의 이메일 로그인 요청 |
| AUTH-0016 | 403 | 정지·탈퇴 등 로그인 제한 상태. LOCAL 계정은 비밀번호 확인 후 구분 |
| AUTH-0017 | 401 | 보호 API의 인증 누락·형식 오류·만료·폐기·제한 회원 |
| AUTH-0018 | 403 | 접근 권한·CSRF 검증 실패 |
| AUTH-0019 | 503 | Bearer 인증 저장소의 일시 장애 |
| AUTH-0020 | 429 | 로그인 요청 제한 초과 |
| 기존 COMMON 코드 | 500 | 로그인 저장 실패 등 내부 오류 |

401 보안 필터 응답에는 WWW-Authenticate: Bearer를 설정한다. 요청 이메일·비밀번호·토큰 원문을 오류 응답에 포함하지 않는다. 허용되지 않은 Origin은 CORS 처리에서 403으로 거부되며, 브라우저는 해당 응답 본문을 읽을 수 없다.

## 임시 설정·선택지·영향·재검토 조건

아래 값은 구현에 필요한 임시 기준이다. 팀에서 확정한 것으로 취급하지 않는다.

| 쟁점 | 임시 구현 기준 | 선택지·영향 | 재검토 조건 |
|---|---|---|---|
| 쿠키 | HttpOnly·Secure·SameSite=None, 호스트 전용, 인증 경로, 최대 8시간 | 다른 사이트의 프론트·API 연동을 고려한 기본값. 브라우저의 타사 쿠키 차단으로 연동이 실패할 수 있음. 같은 사이트 배포라면 Lax·Strict와 비교 필요 | 프론트·API 도메인 및 HTTPS 배포 확정, 실제 브라우저 연동 전 |
| CORS | 환경변수의 정확한 Origin만 허용하고 credentials 활성화. 기본 허용 목록은 비어 있음 | 와일드카드·임의 Preview 주소 자동 허용 없음. 목록이 없으면 다른 Origin의 브라우저 요청은 차단. Preview마다 주소 등록 또는 안정된 테스트 도메인 필요 | 배포·미리보기 도메인 확정 시 |
| CSRF 경계 | JSON 공개 인증 API와 Bearer를 명시한 보호 요청을 구분. 쿠키를 소비할 갱신·로그아웃 경로에는 CSRF 보호 유지. CSRF 상태는 쿠키에 저장해 서버 세션을 생성하지 않음 | 갱신·CSRF 조회 구현은 토큰 갱신 협의 문서 참고. 로그아웃 계약은 [로그아웃 협의 문서](logout-draft.md) 참고. 다른 사이트의 쿠키 요청은 Origin 검증과 CSRF 헤더·쿠키를 함께 사용 | BRM-28/30 구현 및 프론트 연동 전 |
| 로그인 요청 제한 | 이메일의 DB upper 비교값을 해시 키로 사용. 성공·실패 합산 60초에 10회, 초과 429. 실패와 별도 트랜잭션으로 횟수 저장 | 정상 로그인도 같은 한도에 포함. IP·전체 서비스 제한은 별도이며 여러 이메일로 분산한 요청을 막는 전역 제한을 대신하지 않음 | 운영 남용 방지 정책, 처리량·게이트웨이 설정 검토 전 |
| 요청 제한 데이터 | 원문 이메일 대신 해시 키 저장. 24시간 지난 요청 창을 매시간 정리 | 만료된 행은 제한에 영향이 없고 다음 요청 시 새 창 시작. 보존 기간·정리 주기는 임시 기준 | 운영 보존·삭제 기준 확정 시 |
| 입력 상한 | 이메일은 기존 회원 컬럼의 255자 이내. 비밀번호에 신규 가입 조건이나 새 상한을 적용하지 않음 | 가입·로그인·재설정의 비밀번호 최대 길이와 HTTP 요청 크기 제한을 함께 정해야 기존 계정과 충돌하지 않음 | 입력·요청 크기 정책 확정 전 |
| 신규 오류 코드·응답 필드 | AUTH-0014~0020, accessToken·tokenType·expiresIn·sessionExpiresIn 및 /me 계약 | 프론트의 현재 /auth/login·user 응답 가정과 다름. 명세·요청 URL·오류 분기 변경 필요 | 프론트 소비자 구현과 계약 검토 시 |

## 환경변수

| 변수 | 기본값 | 용도 |
|---|---|---|
| LOGIN_JWT_SIGNING_KEY | 없음·필수 | 최소 32바이트 독립 난수 키의 Base64 값. 누락·잘못된 값이면 토큰 발급·검증을 차단 |
| LOGIN_ACCESS_TTL_SECONDS | 900 | Access Token 유효기간. 1~900초 |
| LOGIN_SESSION_TTL_SECONDS | 28800 | 최초 로그인부터 최대 유지 기간. Access 기간 이상·28800초 이하 |
| LOGIN_COOKIE_SECURE | true | 갱신·CSRF 쿠키의 Secure 속성 |
| LOGIN_COOKIE_SAME_SITE | None | None·Lax·Strict 중 선택. None은 Secure=true 필수 |
| LOGIN_ALLOWED_ORIGINS | 비어 있음 | 허용할 정확한 Origin의 쉼표 구분 목록. 경로·와일드카드 제외 |
| LOGIN_MAX_REQUESTS_PER_WINDOW | 10 | 이메일별 창 내 로그인 요청 상한 |
| LOGIN_REQUEST_WINDOW_SECONDS | 60 | 로그인 요청 집계 창의 초 단위 기간 |

HTTP 개발 환경에서만 Secure=false와 SameSite=Lax 또는 Strict 조합을 별도로 지정할 수 있다. 기본 보안 속성을 운영 환경에서 완화하지 않는다. DB와 기존 이메일 인증 설정은 기존 서버 실행 기준을 따른다.

## 데이터·동시성

- Flyway로 refresh_token에 Access·Refresh 해시, Access 만료, 폐기 시각과 제약·인덱스를 추가한다. 기존 행은 보존하며 해시가 없는 기존 메타데이터를 새 인증에 사용하지 않는다.
- 비밀번호 확인 후 발급 직전에 회원 행을 잠그고 ACTIVE·LOCAL·미탈퇴 상태와 비밀번호 해시가 유지되는지 다시 확인한다. 상태나 비밀번호가 바뀌면 발급하지 않는다.
- 동일 회원의 동시 발급은 직렬화하며 마지막으로 커밋된 Refresh만 갱신할 수 있다. 이전 Access는 원래 만료까지 유효하다. 저장 실패 시 트랜잭션을 롤백하고 토큰·쿠키를 반환하지 않는다.
- JWT는 HS256으로 서명하고 iss·aud·sub·jti·iat·exp·token_use·family를 검증한다. 이메일·이름은 claim에 넣지 않는다. 서명 키는 Google 인증 키·메일 HMAC·Outbox 키와 분리한다.
- login_access_token에는 원문 대신 해시·회원·family·만료·폐기 시각만 저장한다. 재로그인·갱신으로 이전 Access를 삭제하지 않으며, 로그아웃·재사용 탐지는 해당 family, 비밀번호 재설정은 해당 회원의 모든 Access를 폐기한다. 만료 행은 매시간 정리한다. 기존 불투명 토큰은 JWT 검증을 통과하지 못하므로 전환 후 재로그인이 필요하다.
- 로그인 최대 만료는 최초 발급 시각으로 저장한다. BRM-30 갱신에서도 이 시각을 연장하지 않는다.

## 검증 및 남은 확인

- 2026-10-13 Auth·Term 통합 구현 기준 `./gradlew check bootJar --no-daemon`: 전체 683개 통과, 실패·오류·건너뜀 0개, JAR 빌드 성공.
- Argon2id 신규 저장·기존 해시 호환, JWT 서명·용도·만료·위조 거부, 재로그인·갱신 후 이전 Access 보존, 계정 제한·로그아웃·Refresh 재사용·비밀번호 재설정의 폐기 범위 및 DB 오류 롤백을 확인했다.
- 선행 리뷰·병합 후 개별 PR의 최신 develop 비교와 원격 CI를 다시 확인한다. 실제 브라우저·Google 계정·인증 방식 변경 후 실제 재설정 메일 연결은 미검증이다.

## 참고

- [Spring Security CORS](https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html)
- [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
