# 로그인 토큰 갱신 구현 및 협의 사항

- 갱신일: 2026-11-04
- 상태: 구현 초안·팀 검토 필요
- 소유 역할: 백엔드·프론트엔드
- 관련 작업: [Issue #28](https://github.com/sw-capstone/bareum-server/issues/28), BRM-30, AUTH-009

## 기준과 범위

- [AUTH-009 명세](https://app.notion.com/p/63711880adea833aadd4811919fa18c9)의 Refresh Token 검증·새 Access Token 발급·만료/폐기/제한 회원 차단을 구현한다. 해당 명세의 교체·저장·폐기 정책은 아직 미정이다.
- [기술·정책 명세](https://app.notion.com/p/3e311880adea8077a667c9a8eae3d107)와 프론트 협의의 Access Token 15분·최초 로그인부터 최대 8시간·메모리 보관·HttpOnly 쿠키 방향을 따른다. 로그인 상태 장기 유지는 추가하지 않는다.
- [PR #37](https://github.com/sw-capstone/bareum-server/pull/37)의 JWT Access·Refresh 해시·회원당 한 현재 로그인 구조를 재사용한다. 이번 범위는 갱신 API와 이를 위한 CSRF 조회·회전 저장·재사용 차단이다. 로그아웃(BRM-28), Google 로그인(BRM-29)은 별도 작업이다.
- 임시 정책을 팀 확정 사항으로 취급하지 않는다. 아래 선택지·영향·재검토 조건에 따라 검토한다.

## 현재 API 계약

### 1. CSRF 토큰 조회

`GET /api/v1/auth/csrf`

- Access Token 없이 `credentials: 'include'`로 호출한다. 만료된 Authorization 헤더가 남아 있어도 이 경로에서는 Bearer 인증을 요구하지 않는다.
- HTTP 200, `Cache-Control: no-store`, `Pragma: no-cache`.

```json
{"headerName":"X-CSRF-TOKEN","token":"<마스킹된 CSRF 토큰>"}
```

- 서버는 `XSRF-TOKEN` 쿠키를 HttpOnly·Secure·SameSite=None·Path=/api/v1/auth로 설정한다. Domain은 지정하지 않는다. 서버 세션이나 JSESSIONID를 생성하지 않는다.
- 프론트는 쿠키를 읽지 않고 응답의 token을 메모리에 보관한다. 쿠키의 원문 값과 응답 token은 다르다. 원문 쿠키 값을 헤더로 보내면 검증에 실패한다.
- 새로고침·CSRF 쿠키 유실 후에는 다시 조회한다. 명시된 Origin만 credentials가 허용되며, 와일드카드·임의 Preview 자동 허용은 하지 않는다.

### 2. 토큰 갱신

`POST /api/v1/auth/token/renew`

```http
Cookie: bareum_refresh=<갱신 토큰>; XSRF-TOKEN=<브라우저 쿠키>
X-CSRF-TOKEN: <CSRF 조회 응답의 token>
```

- 요청 본문과 Access Token은 필요하지 않다. 프론트는 `credentials: 'include'`와 응답의 headerName·token을 사용하고, Cookie 헤더는 브라우저가 전달한다. 본문·Authorization의 토큰은 Refresh Token을 대신하지 않는다.
- `bareum_refresh` 쿠키는 하나만 허용한다. CSRF 값도 하나의 명시적 X-CSRF-TOKEN 헤더만 허용하며 쿼리·폼 필드는 사용하지 않는다.
- 성공 HTTP 200:

```json
{"accessToken":"<새 JWT Access Token>","tokenType":"Bearer","expiresIn":900,"sessionExpiresIn":27000}
```

- 시간은 응답 시점의 남은 초다. Access 기간은 15분과 최대 로그인 남은 시간 중 작은 값이다. 마지막 30초에 갱신하면 두 시간 값은 모두 30초 이내다.
- 새 Refresh Token은 기존과 같은 이름·HttpOnly·Secure·SameSite·Path의 쿠키로만 전달한다. Max-Age는 최초 로그인부터 최대 8시간의 남은 시간이고 갱신으로 연장하지 않는다.
- 이전 Access Token은 원래 만료까지 유효하고, 이전 Refresh Token은 소비 기록으로 전환해 다시 갱신할 수 없다. 새 Access Token으로 보호 API·`GET /api/v1/auth/me`를 호출한다.
- 응답은 캐시하지 않는다. 실패 시 새 Access Token이나 Refresh 쿠키를 발급하지 않는다.

### 3. 오류와 프론트 처리

기존 ProblemDetail의 code·status·detail·instance 구조를 사용한다.

| HTTP·코드 | 조건 | 처리 기준 |
|---|---|---|
| 401 AUTH-0021 | Refresh 쿠키 누락·중복·잘못된 형식·위조·만료·폐기·이전 로그인·해시 없는 기존 메타데이터 | 메모리 인증 정보를 지우고 로그인 화면 이동 |
| 401 AUTH-0022 | 같은 로그인에서 이미 사용한 Refresh Token 재사용 | 해당 로그인 폐기. 새로 로그인 필요 |
| 403 AUTH-0016 | 유효한 Refresh Token의 회원이 정지·탈퇴 등 제한 상태 | 갱신하지 않고 로그인 가능한 상태 안내 |
| 403 AUTH-0018 | CSRF 쿠키·명시적 헤더 누락/불일치·원문/중복 헤더 | CSRF 전달 여부 확인. 토큰 교체는 수행하지 않음 |
| 429 AUTH-0023 | 같은 로그인 세션의 갱신 제한 초과 | 쿠키·기존 유효 토큰 유지. 즉시 반복하지 않음 |
| 503 AUTH-0019 | 인증 DB 조회·저장·커밋 장애 | 새 토큰 발급 없음. 기존 상태는 롤백. 갱신 실패 안내 |

- 허용되지 않은 Origin은 CORS 403으로 차단되며 브라우저에서 본문을 읽을 수 없다.
- CSRF 검증은 업무 검증보다 먼저 수행되므로 유효하지 않은 쿠키와 CSRF 누락이 함께 있으면 403이 먼저 반환된다.
- **프론트는 탭·요청 간 갱신을 직렬화해야 한다.** 동시에 같은 쿠키로 두 요청이 들어오면 한 요청은 성공하지만 다른 요청은 재사용으로 판단되어 해당 로그인 전체가 폐기된다. 여러 API가 동시에 401을 받으면 하나의 갱신 결과를 공유하고 보호 API만 새 Access Token으로 다시 호출한다.
- 갱신 응답이 유실됐다고 이전 Refresh Token으로 자동 재시도하지 않는다. 서버가 이미 교체했다면 재사용 폐기 대상이며 다시 로그인해야 한다. 쿠키 갱신·응답 수신 순서와 다중 탭 조율은 실제 브라우저 연결에서 확인한다.
- 쿠키 유효기간과 타사 쿠키 차단 조건은 실제 프론트·서버 도메인으로 검증해야 한다. 현재 HTTP 테스트 통과가 실제 브라우저 연동 완료를 의미하지 않는다.

## 임시 선택·영향·재검토 조건

| 쟁점 | 임시 구현 기준 | 선택지·영향 | 재검토 조건 |
|---|---|---|---|
| Refresh Token 교체 | 성공마다 새 JWT Access·Refresh 발급. 기존 Access는 원래 만료까지 유지, 이전 Refresh는 소비 처리. 최초 로그인 만료는 유지 | Refresh 고정은 소비자 구현이 간단하지만 탈취 시 반복 사용 위험. 현재 동시 갱신·응답 유실 복구는 제한 | 프론트 갱신 처리 계약 확정 전 |
| 재사용 처리 | 소비된 해시가 현재 로그인 family(jti)와 같고 최대 만료 전이면 현재 로그인 폐기 | 유예 구간·멱등 재시도는 복구에 유리하지만 별도 응답 보관·탈취 판별 필요. 임의 유예는 적용하지 않음 | 네트워크 재시도·다중 탭 사용자 경험 검토 시 |
| 새 로그인 보호 | family는 최초 로그인 시 생성한 jti. 갱신 중 유지하고 재로그인에서 교체 | 과거 로그인의 소비 기록이 새 로그인을 폐기하지 않음. 다른 회원의 세션도 영향 없음 | 다중 기기 로그인 구조 변경 시 |
| CSRF 전달 | CookieCsrfTokenRepository·XOR 마스킹 유지, HttpOnly 쿠키 + GET 응답 토큰 + 단일 헤더 | 읽기 가능한 쿠키 대신 응답 API로 전달. 소비자가 새 조회·헤더 처리를 추가해야 함. 안전한 메서드 조회에도 CORS 적용 | 프론트·API 주소와 HTTPS·타사 쿠키 조건 확정 시 |
| 갱신 요청 제한 | 현재 로그인 세션당 60초에 성공 30회. 초과 429, 토큰·쿠키 교체 없음 | 로그인의 이메일별 제한과 별개. 정상 갱신과 비정상 반복으로 저장 기록이 과도하게 늘어나는 것을 제한. 알 수 없는 토큰의 전역/IP 제한은 별도 필요 | 운영 남용 방지·게이트웨이 설정 결정 시 |
| 소비 기록 보존 | 원문 없이 해시·세션·family·소비 시각·최대 만료만 저장. 최대 만료 후 매시간 삭제 | 최대 만료 전 재사용 검출. 세션 삭제 시 cascade. 24시간 지난 요청 창도 매시간 정리 | 운영 보존·삭제·처리량 기준 확정 시 |
| 오류·계약 | AUTH-0021~0023, /csrf 응답, 기존 로그인 성공 4필드 재사용 | 소비자의 오류 분기·갱신 직렬화와 메모리 복구 처리 추가 필요 | 프론트 소비자 구현·명세 검토 시 |

## 데이터와 트랜잭션

- 신규 Flyway는 used_refresh_token과 token_renew_window를 추가한다. 기존 migration을 수정하지 않으며 기존 Refresh 메타데이터도 보존한다. 이력 조회와 제한은 세션 잠금 안에서 JDBC로 수행한다.
- 로그인과 동일하게 회원(JPA) → 세션 순서로 잠근다. 서명 검증된 회원 ID로 회원·세션을 잠그며 별도 해시 기반 회원 검색을 하지 않는다. 잠금 획득 후 현재 Refresh 해시·회원 상태·폐기·최대 만료를 다시 확인한다.
- Access는 개별 저장하지 않는다. 갱신·CSRF API와 문서는 AuthController로 통합한다. DB 접근은 Repository로 분리한다. 성공 시 소비된 해시 저장·요청 제한·현재 Refresh 해시 교체·새 Access 발급을 한 트랜잭션으로 커밋한다. 기존 Access의 만료를 연장하거나 즉시 폐기하지 않는다. DB 오류면 모두 롤백한다. 토큰 원문은 저장·로그·DTO toString에 포함하지 않는다.
- **재사용 거부 전용 예외만 롤백에서 제외**하여 401을 반환한 뒤에도 세션의 Refresh 폐기와 family 단위 폐기 기록이 유지된다. 다른 업무·저장 오류는 롤백한다. 외부 트랜잭션에서 재사용한다면 호출자의 롤백 정책도 함께 검토해야 한다.
- 로그아웃 API의 폐기·쿠키 삭제·갱신 경쟁 처리는 별도 후속 작업이다.

## 환경변수

| 변수 | 기본값 | 용도 |
|---|---|---|
| TOKEN_RENEW_MAX_REQUESTS_PER_WINDOW | 30 | 세션별 집계 창 내 성공 갱신 상한. 1~100 |
| TOKEN_RENEW_REQUEST_WINDOW_SECONDS | 60 | 갱신 집계 창. 1~3600초 |

로그인의 LOGIN_ACCESS_TTL_SECONDS·LOGIN_SESSION_TTL_SECONDS·LOGIN_COOKIE_SECURE·LOGIN_COOKIE_SAME_SITE·LOGIN_ALLOWED_ORIGINS를 함께 사용한다. 쿠키 속성·Origin은 [로그인 협의 문서](login-authentication-draft.md)의 기준을 따른다.

두 갱신 제한 값은 최신 env v6에 포함하며, #66의 배포 방식에 따라 `/bareum/dev/server/env` SecureString에 저장된 전체 환경파일에 반영한다. 개별 파라미터나 배포 템플릿은 사용하지 않는다. 기존 전체 설정을 유지한 채 두 항목의 반영 여부를 확인하고, 누락됐다면 병합 전에 추가한다. 생성 스크립트는 새 키를 허용하고 원문을 `.env.server`로 전달하므로, 갱신 설정 때문에 조회 목록이나 필수 검증 규칙을 추가하지 않는다. 애플리케이션은 위 범위의 설정값을 검증한다.

## 검증과 적용 조건

- Java 25·PostgreSQL 17 신규 DB에서 `check bootJar`: 전체 625개 중 623개 통과, 실패·오류 0개. 실제 AI 서버가 필요한 PDF 연동 2개는 조건부 생략했다. 갱신·절대 만료·재사용·동시 갱신·재로그인 경쟁·CSRF·저장 실패 롤백과 미등록 토큰 거부를 확인했다. Spotless·Flyway 버전 중복 검사·JAR 빌드와 저장소 정적 검사도 통과했다. 선행 로그인 정리 버전까지 적용한 별도 DB 스키마에서 갱신 SQL 순차 적용·기존 세션 보존·재실행 시 추가 적용 없음도 확인했다. PostgreSQL 18 원격 CI는 재오픈 후 확인한다.
- 미배포 신규 갱신 SQL을 `V2026.11.03.13.36__add_refresh_token_rotation.sql`로 배치했다. 선행 로그인 정리 버전(2026.11.03.13.35)보다 높으며 SQL 본문과 적용된 기존 마이그레이션은 변경하지 않았다. 병합 시 기준 브랜치의 최신 버전을 다시 확인한다.
- #66까지 병합된 develop(`f3f5b87`)을 반영했고, 이미 병합된 로그인·배포 변경은 이번 PR의 변경 범위에서 제외했다. 템플릿 추가분은 제거하고 단일 SSM 환경파일 방식을 따른다. 수정 커밋의 원격 CI와 SSM 전체 환경파일의 두 항목 반영을 확인한 뒤 병합한다. 현재 갱신 API의 브라우저 연동·배포 검증은 완료하지 않았다.
