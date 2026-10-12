# Auth·Term 프론트 연동 안내

- 상태: 구현 기준의 연동 초안 / 공개 계약과 미정 정책은 팀 검토 필요
- 확인일: 2026-10-12
- 소유 역할: 백엔드·프론트엔드
- 관련 작업: [GitHub #22](https://github.com/sw-capstone/bareum-server/issues/22), BRM-25
- 서버 구현 기준: [16934ac](https://github.com/sw-capstone/bareum-server/commit/16934ac0b0cf8eda52ce460d413d766b85fd597b) / [PR #45](https://github.com/sw-capstone/bareum-server/pull/45) 및 선행 인증 PR
- 프론트 대조 기준: [develop dc61644](https://github.com/sw-capstone/bareum-web/commit/dc616444db4b766d83fe93e906573265bcf1a806)의 `authApi.ts`·`contracts.ts`·`httpClient.ts`·`docs/backend-integration.md`

이 문서는 구현된 서버의 요청·응답을 모아 프론트 변경점을 설명한다. 미병합 API가 배포되었다거나 실제 브라우저 연동을 완료했다는 의미는 아니다. [Notion API 명세](https://app.notion.com/p/3e311880adea8014af6bcc19f68c8a59)의 Base URL `/api/v1`과 [최신 기술·정책](https://app.notion.com/p/3e311880adea8077a667c9a8eae3d107)을 기준으로 하며, 세부 계약 확정 전에는 `packages/contracts/`에 공식 계약을 추가하지 않는다.

## API 주소와 성공 응답

Base URL을 `<서버 Origin>/api/v1`로 설정하면 아래 경로를 그대로 붙인다. JSON 본문이 있는 요청은 `Content-Type: application/json`을 사용한다. 로그인 토큰이 필요하지 않은 공개 요청에는 Authorization을 붙이지 않는다.

| 기능 | 메서드·경로 | 성공 | 입력·응답 핵심 | 인증 조건 |
|---|---|---|---|---|
| 이메일 사용 가능 여부 | GET `/auth/email/availability?email=...` | 200 | `{available:boolean}` | 공개 |
| 약관 목록 | GET `/terms` | 200 | `{termId,code,version,required}`의 배열. 원문 없음 | 공개 |
| 인증번호 발송 | POST `/auth/email-verification` | 202 | `{email,purpose}` → `{requestId,status,expiresIn}` | 공개 |
| 인증번호 재발송 | POST `/auth/email-verification/resend` | 202 | 발송과 같은 요청·응답 | 공개 |
| 발송 상태 | GET `/auth/email-verification/deliveries/{requestId}` | 200 | `{requestId,status,expiresIn}` | 공개·추측하기 어려운 요청 ID |
| 인증번호 확인 | POST `/auth/email-verification/code` | 200 | `{email,purpose,code}` → `{verificationToken,expiresIn}` | 공개 |
| 이메일 회원가입 | POST `/auth/signup` | 201 | `{email,name,password,verificationToken,agreements}` → `{memberId}` | SIGNUP 인증 완료 증명 |
| 이메일 로그인 | POST `/auth/login/email` | 200 | `{email,password,keepSignedIn?}` → 로그인 4필드·갱신 쿠키 | 공개·쿠키 수신 |
| CSRF 조회 | GET `/auth/csrf` | 200 | `{headerName,token}`·CSRF 쿠키 | 쿠키 수신·전달 |
| 토큰 갱신 | POST `/auth/token/renew` | 200 | 본문 없음 → 로그인 4필드·교체한 갱신 쿠키 | 갱신 쿠키·CSRF |
| 현재 사용자 | GET `/auth/me` | 200 | `{memberId,name,email}` | Bearer Access Token |
| 로그아웃 | POST `/auth/logout` | 204 | 빈 본문·로그인 폐기·인증 쿠키 삭제 | 쿠키·CSRF |
| Google 로그인 | POST `/auth/login/google` | 200 | `{idToken}` → LOGIN 또는 SIGNUP_REQUIRED | 쿠키·CSRF |
| Google 가입 완료 | POST `/auth/signup/google` | 201 | `{signupToken,agreements}` → 로그인 4필드·갱신 쿠키 | Google 가입 증명·쿠키·CSRF |
| 비밀번호 재설정 | PATCH `/auth/password/reset` | 204 | `{email,verificationToken,password}` → 빈 본문 | PASSWORD_RESET 인증 완료 증명 |

발송 상태 조회의 존재하지 않는 ID는 **404·빈 본문**이다. 모든 오류를 JSON이라고 가정하지 않는다. 약관 목록은 빈 배열을 반환할 수 있으며, 목록 조회 성공만으로 가입용 약관 설정이 준비되었다고 판단하지 않는다.

## 이메일 인증과 가입

purpose는 대문자 enum `SIGNUP` 또는 `PASSWORD_RESET`이다. 프론트의 `signup`·`password-reset`은 전송 전에 변환한다.

발송·재발송 요청:

```json
{"email":"member@example.invalid","purpose":"SIGNUP"}
```

202 접수 응답 예시:

```json
{"requestId":"00000000-0000-4000-8000-000000000001","status":"PENDING","expiresIn":0}
```

응답의 Location 헤더도 발송 상태 조회 경로를 제공한다. PENDING은 대기·전송·재시도를 포함하며 인증 타이머를 시작하지 않는다. 선택한 요청 ID만 조회하고, 재발송에 성공하면 이전 ID 대신 새 ID를 사용한다.

| 상태 조회 예시 | 화면 처리 |
|---|---|
| `{requestId, status:"PENDING", expiresIn:0}` | 대기 안내. 조회 간격·총 대기 한도는 프론트와 별도 합의 |
| `{requestId, status:"SENT", expiresIn:300}` | 서버의 남은 초로 타이머 시작. SMTP 수락이며 받은편지함 도착 보장은 아님 |
| `{requestId, status:"SENT", expiresIn:0}` | 인증번호 만료 안내. 확인 대신 재발송 흐름 제공 |
| `{requestId, status:"FAILED", expiresIn:0}` | 최종 발송 실패 안내. 무한 재시도 중단 |
| `{requestId, status:"CANCELLED", expiresIn:0}` | 교체·인증 완료·사용 등으로 무효화된 요청. 해당 번호의 확인 중단 |

위 상태 표는 필드 설명용 표기다. 실제 응답은 세 필드를 가진 JSON 객체이며, requestId는 202 응답의 UUID다. `300`은 예시이며 운영 유효기간을 확정한 값이 아니다. PENDING/FAILED/CANCELLED에서도 expiresIn=0을 허용하도록 프론트 스키마를 변경해야 한다.

인증번호 확인 요청·성공 응답:

```json
{"email":"member@example.invalid","purpose":"SIGNUP","code":"123456"}
```

```json
{"verificationToken":"<인증 완료 증명>","expiresIn":600}
```

code는 여섯 자리 **문자열**로 전송해 앞자리 0을 보존한다. expiresIn은 인증번호가 아닌 **완료 증명**의 남은 유효기간이다. 증명은 메모리에 보관하고 가입·재설정에만 사용한다. 응답 유실 후 같은 번호를 재확인하면 새 증명을 받는 대신 AUTH-0011로 거부될 수 있다.

가입 요청·성공 응답:

```json
{
  "email":"member@example.invalid",
  "name":"사용자",
  "password":"<가입 조건에 맞는 비밀번호>",
  "verificationToken":"<SIGNUP 인증 완료 증명>",
  "agreements":{"age":true,"service":true,"privacy":true,"marketing":false}
}
```

```json
{"memberId":1}
```

네 약관 항목 모두 boolean으로 보내며 age·service·privacy는 true, marketing은 false도 허용한다. 원문은 프론트에 고정하고 서버는 승인 ID·버전을 연결해 동의 이력을 저장한다. 공식 원문·승인 버전과 age 필수 정책은 아직 팀 확인이 필요하다. 설정·DB 약관이 불일치하면 TERM-0004로 가입을 차단한다. [약관 동의 기준](../decisions/email-verification-draft.md)을 함께 확인한다.

이메일 가입은 **201·memberId 응답 후 별도 로그인**이다. 비밀번호는 현재 구현에서 최소 12자이며 허용 문자는 ASCII 영문·숫자·`!@#$%&`뿐이다. 영문·숫자·특수문자를 각각 포함해야 한다. 서버 정책은 [이메일 가입 문서](../decisions/email-verification-draft.md)를 따른다. 이메일은 대소문자를 무시해 비교하지만 화면에서 수신 주소를 임의로 소문자화하지 않는다.

## 로그인·갱신·사용자 조회·로그아웃

이메일 로그인 요청:

```json
{"email":"member@example.invalid","password":"<입력한 비밀번호>","keepSignedIn":false}
```

로그인·갱신·Google 가입 완료의 성공 본문:

```json
{"accessToken":"<Access Token>","tokenType":"Bearer","expiresIn":900,"sessionExpiresIn":28800}
```

Access Token은 메모리에만 보관하고 보호 API에 `Authorization: Bearer <accessToken>`으로 전달한다. 메뉴 사용자 정보는 로그인 응답의 user 필드 대신 `/auth/me`에서 조회한다. 기본 Access 기간은 15분, 세션은 최초 발급부터 최대 8시간이며 응답의 두 시간은 실제 남은 초다. 갱신은 세션 최대 시간을 연장하지 않는다. keepSignedIn은 생략·null·false만 허용하고 true는 400으로 거부한다.

갱신용 `bareum_refresh` 쿠키는 HttpOnly·Secure·SameSite=None·Path=/api/v1/auth이며 호스트 전용이다. Max-Age는 남은 세션 시간이다. 쿠키를 수신·전달하는 요청은 `credentials: 'include'`를 사용한다. 브라우저가 Cookie를 전달하므로 JavaScript에서 Cookie·Set-Cookie 헤더를 읽거나 조작하지 않는다.

CSRF 조회 응답:

```json
{"headerName":"X-CSRF-TOKEN","token":"<마스킹된 CSRF 토큰>"}
```

`GET /auth/csrf`를 credentials와 함께 호출하면 XSRF-TOKEN 쿠키도 설정된다. 프론트는 **응답 token**을 메모리에 보관하고 갱신·로그아웃·Google 로그인·Google 가입 완료 요청의 X-CSRF-TOKEN 헤더로 보낸다. 쿠키 원문을 헤더 값으로 사용하지 않는다. 쿠키 유실·새로고침·로그아웃 후 새 인증 흐름에서는 다시 조회한다.

- 페이지 새로고침: CSRF 조회 → 갱신 쿠키로 토큰 갱신 → 메모리에 Access Token 저장 → /me 조회.
- 갱신 성공: 이전 Access Token도 무효화되므로 새 값으로 교체한다. 같은 갱신 쿠키의 재사용은 AUTH-0022와 세션 폐기를 유발하므로 한 세션의 갱신을 직렬화한다. 다중 탭도 별도 조정이 필요하다.
- 갱신 실패: 401은 로그인 화면으로 전환한다. 429·503·네트워크 오류를 같은 무효 인증으로 취급하거나 무한 갱신하지 않는다. 재시도·안내 정책은 프론트와 맞춘다.
- 로그아웃 성공: 204를 JSON으로 파싱하지 않고 메모리 토큰·사용자 상태를 비운다. 서버가 갱신·CSRF 쿠키를 삭제한다. Bearer 헤더만으로 서버 세션을 폐기할 수 없다.

현재는 회원당 단일 세션이므로 다른 기기에서 새로 로그인하면 기존 로그인이 교체된다. 세부 정책과 타사 쿠키 제한은 [로그인](../decisions/login-authentication-draft.md)·[갱신](../decisions/token-renew-draft.md)·[로그아웃](../decisions/logout-draft.md) 문서를 따른다. 실제 배포 Origin·HTTPS·타사 쿠키 허용 조건을 확인해야 하며, CORS에는 정확한 Origin만 등록한다.

## Google 로그인과 가입 완료

Google Identity Services callback의 ID Token을 전송한다. Google Access Token·인증 코드·사용자가 입력한 이메일·이름을 대신 보내지 않는다.

```json
{"idToken":"<Google ID Token>"}
```

기존 회원은 200과 갱신 쿠키를 반환한다:

```json
{"status":"LOGIN","accessToken":"<Access Token>","tokenType":"Bearer","expiresIn":900,"sessionExpiresIn":28800}
```

신규 회원은 쿠키·로그인 토큰 대신 가입 증명만 반환한다:

```json
{"status":"SIGNUP_REQUIRED","signupToken":"<Google 가입 증명>","signupExpiresIn":600}
```

SIGNUP_REQUIRED이면 메모리에 signupToken을 보관하고 약관 화면으로 이동한다. `/auth/signup/google`에 아래 본문과 쿠키·CSRF 헤더를 보내면 201·로그인 4필드·갱신 쿠키를 받으며 자동 로그인한다. 요청에서 Google 프로필을 덮어쓰지 않는다.

```json
{"signupToken":"<Google 가입 증명>","agreements":{"age":true,"service":true,"privacy":true,"marketing":false}}
```

같은 이메일의 기존 LOCAL 계정은 AUTH-0027로 이메일 로그인을 안내하며 자동 연결하지 않는다. 현재 신규 가입은 검증된 Gmail·Workspace 이메일만 허용하며 제3자 이메일은 AUTH-0028로 추가 확인을 요구한다. 실제 Google Client ID·승인 Origin과 이 제한 정책은 검토가 필요하다. [Google 로그인](../decisions/google-login-draft.md)·[Google 가입](../decisions/google-signup-draft.md)을 참고한다.

## 비밀번호 재설정

PASSWORD_RESET 목적의 발송 → 상태 조회 → 번호 확인 → PATCH `/auth/password/reset` 순서다. 요청은 email·verificationToken·password이며 성공은 204·빈 본문이다. 등록된 수신 주소로 발송한다. 현재 구현안은 해당 회원의 기존 Access·Refresh 세션을 모두 폐기하며 자동 로그인하지 않는다. 성공 후 메모리 인증 상태를 비우고 로그인 화면으로 이동한다.

최신 정책상 MVP 제외·3순위다. 배포 포함 여부·인증 유효기간·세션 폐기 범위는 팀 확인이 필요하다. [재설정 문서](../decisions/password-reset-draft.md)를 따른다.

## 오류 응답과 프론트 변경점

업무 오류는 `application/problem+json`의 ProblemDetail을 사용한다. 기본 안내는 message 대신 **detail**, 분기는 **code**, HTTP 판단은 실제 응답 status를 사용한다.

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"이메일 인증번호가 일치하지 않습니다.","instance":"/api/v1/auth/email-verification/code","code":"AUTH-0009"}
```

입력 검증 오류는 errors의 `{field,message}` 목록을 추가할 수 있으며 field는 null일 수도 있다. 모든 400에 errors가 있는 것은 아니다. 빈 본문·비JSON 오류도 일반 안내로 처리하고 내부 정보나 입력 원문을 노출하지 않는다.

| 상황 | 대표 코드 | 화면 처리 |
|---|---|---|
| 이미 가입된 이메일 | AUTH-0001 / 409 | 기존 가입 방식의 로그인 안내 |
| 등록되지 않은 재설정 이메일 | AUTH-0002 / 404 | 입력 확인 |
| 번호 만료·불일치·확인 제한 | AUTH-0008 / 400, AUTH-0009 / 400, AUTH-0010 / 429 | 만료·오입력·제한을 구분 |
| 증명 무효·만료 | AUTH-0007·0013 / 400 | 해당 목적의 이메일 인증부터 재시작 |
| 이메일 로그인 실패 | AUTH-0014 / 401 | 이메일·비밀번호 공통 안내 |
| Google 전용·제한 회원 | AUTH-0015·0016 / 403 | 가입 방식·계정 제한 안내 |
| Access 무효 | AUTH-0017 / 401 | 갱신 시도와 세션 복구 판단 |
| CSRF 실패 | AUTH-0018 / 403 | CSRF·Origin 설정 확인 |
| Refresh 무효·재사용 | AUTH-0021·0022 / 401 | 다시 로그인 |
| 필수 약관 미동의·설정 누락 | TERM-0003 / 400, TERM-0004 / 503 | 필수 동의 안내 / 가입 준비 중 안내 |

현재 프론트와의 차이는 다음과 같다. 아래는 소비자 수정이 필요한 항목이며, 서버가 기존 Mock 경로를 추가로 제공한다는 뜻은 아니다.

| 프론트 develop의 현재 가정 | 서버 구현과 맞출 변경 |
|---|---|
| `/api` Base URL, 복수형 email-verifications | `/api/v1`, 단수형 email-verification 경로 |
| 발송 즉시 200·expiresIn>0 | 202 접수·요청 ID·상태 조회·0초 허용 |
| 소문자 purpose·번호 확인 후 증명만 저장 | 대문자 enum·완료 증명의 expiresIn 보관 |
| 가입 204 | 201·memberId 수신, 별도 로그인 |
| 로그인 `{user}` 응답·keepSignedIn=true 가능 | 로그인 4필드·메모리 토큰·/me·keepSignedIn 비활성화 |
| credentials·Bearer·CSRF 없는 공통 HTTP 요청 | 목적별 쿠키·인증 헤더 처리와 갱신 직렬화 |
| `/auth/password-reset` POST | `/auth/password/reset` PATCH·204 |
| 오류 message·문자 코드 | detail·AUTH/TERM/COMMON 코드·선택적 errors·빈 오류 본문 |

프론트 저장소의 `authApi.ts`·`contracts.ts`·`httpClient.ts`와 연동 문서는 소비자 담당자가 함께 반영한다. 계약 확정 후 서버·프론트의 영향받는 검증을 연결한다.

## 검증 범위와 연동 전 확인

- 기준 서버의 `./gradlew check bootJar --no-daemon`: 전체 660개 통과, 실패·오류·건너뜀 0개 및 JAR 빌드 성공. HTTP·DB·OpenAPI·동시성·롤백 검사이며 실제 웹 소비자 검증과 구분한다.
- 이번 문서는 Controller·DTO·보안 설정·오류 생성 코드와 지정한 프론트 develop 코드·문서를 대조했다. 실제 브라우저·Google 계정·재설정 메일 수신을 검증했다고 취급하지 않는다.
- 계약 확인 필요: Outbox 조회 주기·대기 한도, Google 인증 분기와 지원 계정, 공식 약관 원문·버전·필수 항목, 배포 Origin·쿠키 조건, 비밀번호 찾기 배포 범위.
- 운영 준비 필요: 메일 발송 정책·완료 증명 기간·실패 제한, 약관 DB와 승인 버전, Google Client ID와 승인 Origin. 필수 설정 누락 시 서버가 차단하며 테스트 값을 운영 기본값으로 사용하지 않는다.
- 실제 연동 검증: 이메일 가입→로그인→/me→갱신→로그아웃, Google 기존·신규 가입, Outbox 대기·실패·재발송, 만료·CSRF·타사 쿠키·다중 탭 오류 흐름. 비밀번호 재설정은 배포 범위 확정 후 검증한다.
