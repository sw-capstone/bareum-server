# Auth·Term 프론트 연동 안내

> 실패 5회·15분 대기, 완료 증명 분실 시 재인증, 재설정 증명 600초와 Google 신규 가입 지원 범위는 [임시 적용 기준](../decisions/auth-term-policy-implementation.md)을 따른다. 개발 적용안이며 팀 최종 승인·실제 배포와 구분한다.

실제 배포·브라우저 검증 절차는 [연동 검증 시나리오](auth-term-validation.md)를 따른다.

- 상태: 구현 기준의 연동 초안 / 공개 계약과 미정 정책은 팀 검토 필요
- 확인일: 2026-10-13
- 소유 역할: 백엔드·프론트엔드
- 관련 작업: [GitHub #22](https://github.com/sw-capstone/bareum-server/issues/22), BRM-25
- 서버 구현 기준: [PR #46](https://github.com/sw-capstone/bareum-server/pull/46), [PR #45](https://github.com/sw-capstone/bareum-server/pull/45) 및 선행 인증 PR
- 프론트 대조 기준: [develop dc61644](https://github.com/sw-capstone/bareum-web/commit/dc616444db4b766d83fe93e906573265bcf1a806)의 `authApi.ts`·`contracts.ts`·`httpClient.ts`·`docs/backend-integration.md`
- 프론트 화면 확인: [develop 미리보기](https://bareum-web-git-develop-numkites-projects.vercel.app/)

이 문서는 구현된 서버의 요청·응답을 모아 프론트 변경점을 설명한다. 미병합 API가 배포되었다거나 실제 브라우저 연동을 완료했다는 의미는 아니다. [Notion API 명세](https://app.notion.com/p/3e311880adea8014af6bcc19f68c8a59)의 Base URL `/api/v1`과 [최신 기술·정책](https://app.notion.com/p/3e311880adea8077a667c9a8eae3d107)을 기준으로 하며, 세부 계약 확정 전에는 `packages/contracts/`에 공식 계약을 추가하지 않는다.

## 구현·설정 상태 구분

정책의 확정 여부, 코드 구현 여부, 배포 환경 적용 여부를 별도로 판단한다.

| 구분 | 의미 |
| --- | --- |
| 명세·합의 기준으로 구현 | 최신 정책 또는 담당자 간 합의에 따라 코드에 반영한 기준. 실제 배포·브라우저 연동 완료를 뜻하지 않음 |
| 임시 기준으로 구현·팀 확인 필요 | 코드에 현재 동작과 임시 기본값이 있음. 팀 확정 정책이 아니며 변경 시 구현·검증도 함께 수정 |
| 설정 경로 구현·실제 값 준비 필요 | 값을 받는 코드와 배포 주입 경로는 있음. 승인된 운영 값은 아직 정하지 않았으며 배포 적용도 확인하지 않음 |
| 프론트 연결 필요 | 메일 1초 조회·SENT 타이머·FAILED 문구는 전달 기준이며 프론트 구현·실제 검증은 남아 있음. 별도 응답 유실 복구는 추가 확인 |

실제 Parameter Store 값·권한·EC2 적용은 별도 확인 대상이다. 미정 값에 테스트 설정을 운영 기본값으로 적용하지 않는다.

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

### 비동기 메일 발송 연동

최초·재발송 모두 발송 작업을 먼저 접수하고 별도 worker가 메일을 보내는 방식이다. 프론트 전달 기준은 **202 접수 → 요청 ID로 1초 간격 상태 조회 → SENT의 남은 시간으로 타이머 시작 → FAILED 확인 시 최종 실패 안내**다. PENDING의 지연 안내나 1분 초과에 따른 실패 처리는 추가하지 않는다. 상태 조회의 일시적 통신 오류는 발송 실패로 간주하지 않고 재조회한다. 아래는 연동 기준이며 프론트 구현·실제 브라우저 검증 완료를 의미하지 않는다. 최초 접수 응답 유실 등 별도 복구 계약은 추가 확인 대상이다.

1. 최초 발송은 `POST /api/v1/auth/email-verification`, 재발송은 `POST /api/v1/auth/email-verification/resend`를 호출한다. 두 요청의 본문 형식은 같다.
2. 202 응답의 `requestId`를 저장하고 `GET /api/v1/auth/email-verification/deliveries/{requestId}`를 조회한다. 상태 조회 성공은 200이며 PENDING은 계속 조회할 대기 상태다.
3. PENDING에서는 인증 타이머를 시작하지 않는다. SENT와 `expiresIn > 0`을 확인하면 조회를 멈추고 서버의 남은 초로 인증 타이머를 시작한다.
4. FAILED에서는 조회를 중단하고 “메일을 보내지 못했습니다. 다시 시도해주세요.”를 표시한다. CANCELLED는 무효화로 구분해 조회를 중단하고 타이머를 시작하지 않는다. SENT의 0초는 만료로 처리한다. 404는 빈 본문으로 요청 확인 불가를 처리한다.
5. 재발송 202를 받으면 새 `requestId`로 교체한다. 이전 ID의 늦은 조회 응답은 화면에 반영하지 않는다. 재발송이 거부된 경우에는 기존 ID·번호 상태를 유지한다. 조회는 1초 간격을 기준으로 요청이 중첩되지 않게 처리하고 화면 이탈 시 중단한다.

purpose는 대문자 enum `SIGNUP` 또는 `PASSWORD_RESET`이다. 프론트의 `signup`·`password-reset`은 전송 전에 변환한다. 비밀번호 재설정의 배포 포함 여부는 별도 확인한다.

발송·재발송 요청:

```json
{"email":"member@example.invalid","purpose":"SIGNUP"}
```

202 접수 응답 예시:

```json
{"requestId":"00000000-0000-4000-8000-000000000001","status":"PENDING","expiresIn":0}
```

응답은 `Location: /api/v1/auth/email-verification/deliveries/{requestId}`와 `Cache-Control: no-store`도 제공한다. 프론트는 본문의 requestId로 조회 URL을 만들 수 있으며 Location을 JavaScript에서 읽을 필요는 없다. requestId는 인증 요청의 DB ID가 아닌 **발송 작업의 UUID**다. 접수·조회 응답에는 이메일·인증번호·해시·암호문을 포함하지 않는다.

상태 조회 요청 예시:

```http
GET /api/v1/auth/email-verification/deliveries/00000000-0000-4000-8000-000000000001
```

200 발송 완료 응답 예시:

```json
{"requestId":"00000000-0000-4000-8000-000000000001","status":"SENT","expiresIn":300}
```

200 최종 발송 실패 응답 예시:

```json
{"requestId":"00000000-0000-4000-8000-000000000001","status":"FAILED","expiresIn":0}
```

200 무효화 응답 예시:

```json
{"requestId":"00000000-0000-4000-8000-000000000001","status":"CANCELLED","expiresIn":0}
```

| 필드 | 형식 | 의미 |
| --- | --- | --- |
| requestId | UUID 문자열 | 현재 화면에서 조회하는 발송 작업 ID |
| status | PENDING·SENT·FAILED·CANCELLED | 발송 상태. HTTP 200만으로 발송 완료를 판단하지 않음 |
| expiresIn | 0 이상의 정수·초 | SENT의 인증번호 잔여 시간. 다른 상태는 0 |

인증번호를 확인하거나 가입·재설정에 사용한 작업도 조회 시 CANCELLED로 표시될 수 있다. **이미 성공한 인증 확인·가입 응답을 늦은 상태 조회로 취소하지 않도록**, 다음 단계로 이동하면 발송 상태 조회를 종료한다.

| 상태 조회 예시 | 화면 처리 |
|---|---|
| `{requestId, status:"PENDING", expiresIn:0}` | 타이머·별도 지연 안내 없이 1초 간격 조회 유지. 시간 초과만으로 실패 처리하지 않음 |
| `{requestId, status:"SENT", expiresIn:300}` | 서버의 남은 초로 타이머 시작. SMTP 수락이며 받은편지함 도착 보장은 아님 |
| `{requestId, status:"SENT", expiresIn:0}` | 인증번호 만료 안내. 확인 대신 재발송 흐름 제공 |
| `{requestId, status:"FAILED", expiresIn:0}` | 조회 중단. “메일을 보내지 못했습니다. 다시 시도해주세요.” 표시 |
| `{requestId, status:"CANCELLED", expiresIn:0}` | 교체·인증 완료·사용 등으로 무효화된 요청. 해당 번호의 확인 중단 |

위 상태 표는 필드 설명용 표기이며 실제 응답은 위 JSON 예시처럼 세 필드를 반환한다. **SIGNUP 인증번호는 최신 정책에 따라 SMTP 수락 후 5분(300초)**이며, 조회 시점의 expiresIn은 그보다 작을 수 있다. **SIGNUP 완료 증명은 인증 완료 후 10분(600초)**다. PASSWORD_RESET 번호는 개발 서버 v2에서 300초를 임시 선택했으며 팀 확인이 필요하다. 재설정 완료 증명은 별도 설정으로 기본 600초를 임시 적용한다. PENDING/FAILED/CANCELLED에서도 expiresIn=0을 허용하도록 프론트 스키마를 변경해야 한다.

재발송 접수가 커밋되면 이전 번호는 무효화하고, 새 번호는 SENT가 되기 전까지 확인할 수 없다. 재발송으로 인증 실패 횟수를 초기화하지 않는다. 접수 후 메일 전송 실패는 상태 조회의 FAILED로 확인하며 이미 반환한 202를 동기 오류 응답으로 바꾸지 않는다. 접수 자체가 거부되는 400·409·429·503 등의 오류는 별도로 처리한다.

SMTP 수락 뒤 DB 기록 실패·worker 복구 시에는 **같은 작업·같은 번호의 메일이 중복 도착할 수 있다**. 이 메일을 별도의 재발송 성공이나 새 타이머 시작으로 취급하지 않는다. 접수 응답이 유실됐다고 POST를 무조건 반복하지 않는다. 멱등 접수·복구 방식은 미확정이다.

### 인증번호 확인과 이메일 가입

`POST /api/v1/auth/email-verification/code` 요청·200 성공 응답:

```json
{"email":"member@example.invalid","purpose":"SIGNUP","code":"123456"}
```

```json
{"verificationToken":"<인증 완료 증명>","expiresIn":600}
```

code는 여섯 자리 **문자열**로 전송해 앞자리 0을 보존한다. expiresIn은 인증번호가 아닌 **완료 증명**의 남은 유효기간이다. 증명은 메모리에 보관하고 가입·재설정에만 사용한다. 응답 유실 후 같은 번호를 재확인하면 새 증명을 받는 대신 AUTH-0011로 거부될 수 있다.

`POST /api/v1/auth/signup` 요청·201 성공 응답:

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

네 약관 항목 모두 boolean으로 보내며 age·service·privacy는 true, marketing은 false도 허용한다. 원문은 프론트가 관리하며 개발 중 임시 원문을 사용할 수 있다. 서버는 DB 약관의 ID·버전으로 동의 이력을 기록하며 별도 버전 환경변수·비교는 사용하지 않는다. [약관 동의 기준](../decisions/signup-term-consent.md)을 따른다.

이메일 가입은 **201·memberId 응답 후 별도 로그인**이다. 최신 기술 정책의 최소 12자·영문/숫자/`!@#$%&` 각 1개 이상·공백 금지를 적용한다. 이전 AUTH-001의 8~12자 문구는 적용하지 않는다. 필수 조건을 충족하는 비밀번호의 다른 비공백 문자를 추가로 금지하지 않는다. [이메일 가입 문서](../decisions/email-verification-draft.md)를 따른다. 이메일은 대소문자를 무시해 비교하지만 화면에서 수신 주소를 임의로 소문자화하지 않는다.

## 로그인·갱신·사용자 조회·로그아웃

서비스 로그인은 기존 팀 기준의 JWT Access·Refresh와 Rotation을 사용한다. 재로그인·갱신 시 이전 Access는 원래 만료까지 유지하고 현재 Refresh만 갱신할 수 있다. JWT를 프론트에서 해석하거나 영구 저장하지 않으며 API 응답의 남은 시간을 사용한다.

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
- 갱신 성공: 메모리의 Access를 새 값으로 교체한다. 이전 Access는 원래 만료까지 유효하며, 이전 Refresh는 재사용하면 안 된다. 같은 갱신 쿠키의 재사용은 AUTH-0022와 세션 폐기를 유발하므로 한 세션의 갱신을 직렬화한다. 다중 탭도 별도 조정이 필요하다.
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

같은 이메일의 기존 LOCAL 계정은 AUTH-0027로 이메일 로그인을 안내하며 자동 연결하지 않는다. 현재 신규 가입은 검증된 Gmail·Workspace 이메일만 허용하며 제3자 이메일은 AUTH-0028로 지원 제한을 안내한다. 실제 Google Client ID·승인 Origin과 이 제한 정책은 검토가 필요하다. [Google 로그인](../decisions/google-login-draft.md)·[Google 가입](../decisions/google-signup-draft.md)을 참고한다.

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
| 재설정 불가·발송 제한·설정 누락 | AUTH-0003 / 400, AUTH-0004 / 429, AUTH-0006 / 503 | 가입 방식·발송 제한·서비스 준비 상태 안내 |
| 이미 인증 완료 | AUTH-0011 / 409 | 보관한 완료 증명으로 다음 단계 진행. 증명 응답 유실 시 복구 방식 별도 확인 |
| 증명 무효·만료 | AUTH-0007·0013 / 400 | 해당 목적의 이메일 인증부터 재시작 |
| 이메일 로그인 실패 | AUTH-0014 / 401 | 이메일·비밀번호 공통 안내 |
| Google 전용·제한 회원 | AUTH-0015·0016 / 403 | 가입 방식·계정 제한 안내 |
| Access 무효 | AUTH-0017 / 401 | 갱신 시도와 세션 복구 판단 |
| CSRF 실패 | AUTH-0018 / 403 | CSRF·Origin 설정 확인 |
| Refresh 무효·재사용 | AUTH-0021·0022 / 401 | 다시 로그인 |
| Google 인증·가입 분기 | AUTH-0027 / 409, AUTH-0028 / 403, AUTH-0029 / 401 | 이메일 로그인 안내 / Gmail·Workspace 신규 가입 지원 범위 안내 / Google 인증 다시 시작 |
| 필수 약관 미동의·DB 데이터 오류 | TERM-0003 / 400, TERM-0004 / 500 | 필수 동의 안내 / 서버 오류 안내. 별도 버전 비교 503 없음 |

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

## 실제 연동 전 반영할 항목

대조한 프론트 develop·미리보기는 Mock 인증이며 Google 인증 처리와 실제 서버 쿠키 연동은 연결되지 않았다. 로그인 상태 유지도 기본 선택되어 있어 비활성화가 필요하다. 이 결과는 상단에 명시한 프론트 commit 기준이다.

1. 인증 API가 배포된 HTTPS 서버의 `/api/v1` 주소를 설정하고, 이 문서의 경로·응답·오류 계약을 반영한 뒤 Mock을 끈다.
2. 최초·재발송의 202 접수·요청 ID·상태 조회와 번호·완료 증명의 별도 타이머를 연결한다.
3. 메모리 Access Token·/me, credentials·CSRF·갱신 직렬화·로그아웃·새로고침 복구를 연결한다. 사용자 표시 정보만으로 인증 여부를 판정하지 않는다.
4. Google SDK의 ID Token과 LOGIN·SIGNUP_REQUIRED·약관 분기를 연결한다. 실제 Client ID·승인 Origin과 지원 계정은 확인이 필요하다.
5. 실제 프론트 Origin·HTTPS·쿠키·CSRF를 브라우저에서 검증한다. 비밀번호 찾기는 배포 포함 여부 확정 후 연결한다.

명세 충돌과 기존 리뷰의 적용 기준은 [Auth·Term 기준 정비](../decisions/auth-term-review-alignment.md)를 따른다.

## 서버 연동 준비 상태

아래 DB·Parameter Store·메일 설정은 백엔드·인프라 담당자의 준비 사항이다. 프론트는 공개 API만 소비한다. API 구현과 실제 배포·연동 준비를 구분하며, 관련 정책이 승인되면 환경에 적용한다.

| 항목 | 필요한 준비 | 누락 시 동작·확인 기준 |
|---|---|---|
| 서버 기동·DB | DB 접속 정보, Flyway 적용, `EMAIL_VERIFICATION_HASH_SECRET` | HMAC 키는 Base64 형식으로 최소 32바이트가 필요하다. 잘못된 키는 서버 기동을 막는다. `/health` 성공만으로 모든 인증 기능이 준비됐다고 판단하지 않는다. |
| 브라우저 인증 | HTTPS 서버 주소, `LOGIN_ALLOWED_ORIGINS`, 쿠키·CSRF | CORS에 실제 프론트 Origin을 정확히 등록한다. 개발 적용안은 `https://bareum-web-git-develop-numkites-projects.vercel.app` 하나를 정확히 등록하는 것이다. 문서상의 선택이며 실제 배포 적용·브라우저 검증은 별도다. |
| 메일 발송 | `spring.mail`의 제공자·인증·TLS 설정, 발신 주소, Outbox 암호화 키와 활성화 | `EMAIL_VERIFICATION_MAIL_FROM`과 Base64 형식의 32바이트 `EMAIL_VERIFICATION_OUTBOX_ENCRYPTION_KEY`가 필요하다. 키·발송 설정·제한 정책이 누락되면 발송을 차단한다. 운영 제공자·발신 계정은 별도 결정한다. |
| 인증 제한 | 최소 발송 간격·시간당/일당 횟수·인증 실패 횟수 | `auth.email-verification.minimum-send-interval-seconds`, `max-sends-per-hour`, `max-sends-per-day`와 `max-verification-attempts`를 정한다. 발송 임시안은 60초·최근 1시간 5회·24시간 20회이며 팀 확인·환경 적용이 필요하다. 인증 실패는 기본 5회·15분 제한 후 새 번호로 재시작한다. 재발송 자체로는 초기화하지 않는다. |
| 가입 약관 | 네 약관의 DB 데이터 | 네 boolean 응답을 DB의 약관 ID·버전과 연결한다. 필수 동의를 검증하며 별도 환경변수·버전 비교 503은 사용하지 않는다. |
| Google 인증 | `GOOGLE_CLIENT_IDS`, Google Console의 승인 Origin | ID Token 방식의 실제 웹 Client ID를 프론트 SDK와 서버 검증에 맞춘다. 미설정 시 AUTH-0025로 차단하며 실제 Google 계정 검증은 아직 남아 있다. |
| 비밀번호 찾기 | 배포 범위, 재설정용 인증번호·완료 증명 유효기간 | 구현과 기존 메일 경로 검증은 있으나 JWT·Argon2id 변경 후 실제 메일·브라우저 연결은 미검증이다. 최신 정책의 MVP 제외 기준을 유지하고 `EMAIL_VERIFICATION_PASSWORD_RESET_CODE_TTL_SECONDS`와 `EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS` 중 번호는 300초 임시안, 완료 증명은 600초 임시안을 사용한다. |

발송 제한·실패 복구·약관 원문·Google 지원 계정처럼 미확정인 정책은 테스트 값으로 확정하지 않는다. 완료 증명을 잃으면 기존 재발송 API로 새 번호를 받아 다시 인증한다. 접수가 커밋되면 이전 미사용 증명은 무효화하며, 거부·저장 실패 시에는 보존한다. 메일 화면 전달 기준은 1초 상태 조회·SENT의 남은 시간으로 타이머 시작·FAILED 시 오류 문구 표시다. 별도 지연 안내·1분 초과 실패는 추가하지 않으며 프론트 구현과 실제 연동 검증은 남아 있다.

Parameter Store 준비 스크립트에 Auth·Term 설정 주입 경로를 추가했다. `.env.deploy`의 `AUTH_ENV_ENABLED=true`로 활성화하며 필수 값 누락·검증 실패 시 기존 환경 파일을 유지한다. 설정 목록과 적용 순서는 [Auth·Term 배포 설정 준비](../guide/auth-term-deployment.md)를 따른다. 실제 파라미터·권한·약관 DB·EC2 적용은 인프라 담당자와 연결해야 하며, 코드 추가를 원격 적용 완료로 취급하지 않는다. 상세 메일·약관 설정은 [이메일 인증·가입 기준](../decisions/email-verification-draft.md), 인증 쿠키는 [로그인 기준](../decisions/login-authentication-draft.md), Google 설정은 [Google 로그인 기준](../decisions/google-login-draft.md)을 따른다.

## 검증 범위와 연동 전 확인

- Auth·Term 통합 정비 기준의 `./gradlew check bootJar --no-daemon`: 전체 684개 통과, 실패·오류·건너뜀 0개 및 JAR 빌드 성공. HTTP·DB·OpenAPI·동시성·롤백 검사이며 실제 웹 소비자 검증과 구분한다.
- API 경로·요청/응답·상태·오류 안내는 Controller·DTO·보안 설정과 대조했다. 실제 브라우저·Google 계정 연동은 미검증이다. 재설정 메일·HTTP 연결은 [재설정 문서](../decisions/password-reset-draft.md)의 검증 범위를 따른다.
- 연동 확인 필요: 메일 1초 조회·SENT 타이머·FAILED 오류 문구의 프론트 연결, Google 인증 분기와 지원 계정, 개발 약관 동의 흐름, 실제 사용자 적용 전 공식 원문·기록 기준, 배포 Origin·쿠키 조건, 비밀번호 찾기 배포 범위.
- 운영 준비 필요: 발송 간격 60초·시간당 5회·일일 20회와 재설정 번호 300초 임시안의 팀 확인·환경 적용, 인증 실패 5회·대기 900초·재설정 완료 증명 600초 임시안의 적용, 약관 DB·승인 버전, Google Client ID·승인 Origin. SIGNUP 번호 5분·완료 증명 10분은 정책 기준을 유지한다. 필수 설정 누락 시 서버가 차단하며 테스트 값을 운영 기본값으로 사용하지 않는다.
- JWT·Argon2id 변경 전에는 Gmail SMTP 실제 수신→인증 확인→비밀번호 재설정→새 비밀번호 로그인·/me를 HTTP·DB로 검증했다. 변경 후 서버 회귀 검사는 모의 메일 경로를 포함하며 실제 메일·브라우저 연결은 미검증이다. 이전 검증을 현재 인증 방식의 운영 배포 승인으로 취급하지 않는다.
- 남은 브라우저 연동 검증: 이메일 가입→로그인→/me→갱신→로그아웃, Google 기존·신규 가입, Outbox 대기·실패·재발송, 만료·CSRF·타사 쿠키·다중 탭 오류 흐름. 비밀번호 재설정의 브라우저 검증은 배포 범위 확정 후 진행한다.

## 임시 구현 기준과 팀 협의 사항

아래는 기존 명세에 세부 기준이 없어 구현을 위해 임의로 선택한 내용이며 팀 확정 정책이 아니다. 현재 코드의 동작을 설명하기 위한 기준으로, 각 협의 문서의 선택지·영향·재검토 조건을 함께 확인한다.

Access 15분·최초 로그인부터 최대 8시간·메모리 보관·HttpOnly 갱신 쿠키·/me 제공 방향, 네 boolean 약관 동의와 202 접수·발송 상태 조회 흐름은 아래 미정 선택과 구분한다. 약관은 임시 원문으로 개발하고 최종 원문은 추후 확정한다. SIGNUP 번호 5분·완료 증명 10분과 로그인 상태 장기 유지·계정 연결의 MVP 제외도 기존 정책을 유지한다.

### 임시 기준으로 구현됐고 팀 확인이 필요한 항목

| 항목 | 임시 구현 기준 | 영향·팀 확인 및 재검토 조건 |
| --- | --- | --- |
| 공개 응답·오류 계약 | 위 API별 필드·상태 코드, ProblemDetail과 AUTH/TERM 코드, 상태 조회 404·빈 본문 | 기본 비동기 흐름 외의 필드·오류·응답 유실 복구는 소비자 계약 검토가 필요하다. 계정 존재를 구분하는 오류·안내의 공개 범위도 함께 검토한다. |
| 이메일·탈퇴 주소 재사용 | 대소문자 비교는 리뷰 방향에 맞춰 upper(email)로 일치. 탈퇴 완료(`DELETED`와 탈퇴 시각 모두 있음) 주소는 대기 없이 새 계정으로 재가입 허용 | 즉시 재가입과 탈퇴 완료 판정은 미확정 임시 정책이다. 불완전 탈퇴·정지 계정은 주소를 점유한다. 기존 계정 데이터·권한을 승계하지 않으며 Google sub 재사용까지 허용한 것은 아니다. [이메일·가입 기준](../decisions/email-verification-draft.md) 참고. |
| 인증번호·완료 증명 | 6자리 숫자 문자열, 완료 증명은 해시 저장·일회 사용. 이메일·목적의 최신 인증 요청만 최종 사용 | 이미 완료된 번호 재확인은 409로 거부하며 증명 분실은 재발송 후 재인증한다. 기존 원문 증명은 재조회하지 않는다. [이메일 인증 기준](../decisions/email-verification-draft.md) 참고. |
| 쿠키·CSRF·CORS | Refresh·CSRF 쿠키는 HttpOnly·Secure·SameSite=None·호스트 전용·Path=/api/v1/auth. CSRF는 조회 응답의 마스킹 token을 헤더로 전달. 정확한 Origin만 허용 | HttpOnly 방향은 합의됐지만 SameSite·경로·CSRF 전달 세부는 구현 선택이다. 실제 도메인·타사 쿠키·브라우저 연동 전 재검토한다. [로그인](../decisions/login-authentication-draft.md)·[갱신](../decisions/token-renew-draft.md) 기준 참고. |
| 토큰 갱신·로그아웃 | 갱신 성공마다 새 JWT 두 개를 발급하고 이전 Access는 만료까지 유지한다. 같은 family의 소비된 Refresh 재사용 시 해당 family의 Access·Refresh를 폐기. 로그아웃은 쿠키 세션 폐기·204·두 쿠키 삭제 | 갱신 재시도 유예·멱등 응답은 제공하지 않는다. 갱신/로그아웃 직렬화·다중 탭·응답 유실 처리를 맞춘다. [갱신](../decisions/token-renew-draft.md)·[로그아웃](../decisions/logout-draft.md) 기준 참고. |
| 로그인·갱신 제한 | 이메일별 로그인 성공·실패 합산 60초에 10회, 세션별 성공 갱신 60초에 30회 | 운영 수치·전역/IP 제한·기록 보존 기준은 팀 미확정이다. 배포 처리량·남용 방지 정책을 정할 때 재검토한다. |
| Outbox 처리 | 최초 포함 최대 3회, 재시도 5초, lease 120초, 최대 대기 300초, worker 조회 간격 1초·배치 10개. 같은 작업·번호로 복구 | worker 기본값은 임시 운영안이며 사용자 발송 제한과 프론트 조회 간격을 정한 것이 아니다. SMTP·DB 경계의 중복 메일 가능성과 장애 복구를 검토한다. [Outbox 기준](../decisions/email-verification-draft.md) 참고. |
| 재발송·최종 실패 복구 | 접수 커밋 시 이전 번호·미사용 완료 증명 무효화. 진행 중 추가 접수는 429. 실패 5회 후 900초가 지나 새 번호를 접수하면 시도 횟수 초기화. 최종 실패 후 조건 충족 시 새 작업 접수 | 무효화 시점·완료 증명 보존/폐기·실패 복구·접수 응답 유실에 대한 사용자 흐름을 확정한다. 인증 실패 횟수는 기존 명세대로 재발송으로 초기화하지 않는다. [재발송 기준](../decisions/email-resend-outbox-draft.md) 참고. |
| 약관 매핑 | age·service·privacy 필수, marketing 선택. DB 약관에 동의·버전·시각 연결 | 개발 중 임시 원문을 허용한다. 실제 사용자 적용 전 표시 원문과 DB 버전을 맞춘다. [약관 기준](../decisions/signup-term-consent.md) 참고. |
| Google 인증·가입 증명 | SDK ID Token JSON 방식, 신규 가입 증명 최대 10분·동일 sub의 최신 증명만 유지. 신규 이메일은 검증된 Gmail·Workspace만 허용 | 전달 방식·기간·제3자 이메일의 추가 인증·탈퇴 sub 재가입 기준은 미확정이다. 실제 Google SDK·지원 계정 검토 시 [Google 로그인](../decisions/google-login-draft.md)·[가입](../decisions/google-signup-draft.md) 기준을 재검토한다. |
| 비밀번호 재설정 | 최종 입력 필드는 password, 성공 204·별도 로그인. 회원의 기존 Access·Refresh 세션 모두 폐기 | 입력·응답·폐기 범위는 임시 계약이다. MVP 제외·3순위는 유지하며 배포 포함과 번호 300초 임시안의 승인 여부·완료 증명 600초의 임시안을 검토한다. [재설정 기준](../decisions/password-reset-draft.md) 참고. |

### 설정 경로는 구현됐지만 승인된 실제 값이 필요한 항목

아래 항목은 임시 운영 값을 적용한 상태가 아니다. 설정을 받는 코드가 준비된 상태이며 승인된 값의 결정·배포 적용이 필요하다. 실제 Parameter Store·EC2 설정을 조회해 적용 완료로 확인한 것은 아니다.

| 항목 | 현재 상태 | 필요한 준비 |
| --- | --- | --- |
| 운영 메일 서비스·발신 계정 | SMTP 설정 경로 구현·개발 Gmail 실제 수신 검증 완료. 운영 서비스·계정은 미정 | 운영 제공자·발신 주소·자격증명·TLS 설정 결정 및 적용 |
| 사용자별 메일 발송·인증 제한 | 제한 검사·설정 누락 시 차단 구현. 발송 임시안 60초·최근 1시간 5회·24시간 20회 작성 | 임시안 검토·환경 적용. 인증 실패 5회·15분 대기·재인증 방식은 개발 적용안이며 Outbox 재시도와 별개 |
| 약관 동의 | 네 boolean 검증·DB 버전 연결·이력 저장 | 개발용 네 항목 데이터를 준비하고 실제 사용자 적용 전 원문·DB 버전 대응 확인 |
| 실제 허용 Origin·Google Client ID | Origin·Client ID 검증 경로 구현. 현재 Vercel 주소를 계속 사용할지는 확인 대상 | 프론트 주소 확정 후 백엔드 담당 이서현이 웹 Client ID 생성·공유, 인프라와 서버 설정 적용, 프론트 SDK·서버 Client ID 일치 |
| 비밀번호 찾기 배포·유효기간 | 재설정 코드와 개발 메일 검증 완료. MVP 제외 기준 유지 | 번호 300초 임시안의 팀 확인·환경 적용, 배포 포함 여부·완료 증명 유효기간 결정 |

### 프론트 처리 기준을 추가로 맞춰야 하는 항목

서버는 202 접수·발송 상태·SENT 남은 시간을 제공한다. 프론트 전달 기준은 1초 간격 조회, SENT의 expiresIn으로 타이머 시작, FAILED 시 “메일을 보내지 못했습니다. 다시 시도해주세요.” 표시다. PENDING 지연 안내와 1분 초과 실패 처리는 제외한다. 프론트 구현·실제 브라우저 검증과 최초 접수/인증/갱신 응답 유실 복구는 별도 확인한다. 이 기준은 서버 API 미구현이나 Outbox worker 기본값 변경을 의미하지 않는다.

배포 설정의 임시 선택과 실제 값 준비·권한·키 교체 사항은 [배포 설정 준비](../guide/auth-term-deployment.md)의 마지막 절을 따른다.
