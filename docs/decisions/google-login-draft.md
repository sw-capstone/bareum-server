# Google 로그인 적용 기준

- 대상: AUTH-004 · BRM-29 · GitHub #26 / #40
- 상태: 구현안·팀 검토 필요
- 기준: [AUTH-004](https://app.notion.com/p/57011880adea8339956d010fe1c43f17)와 기술·정책 명세의 Google 인증 검증·기존 회원 로그인·신규 회원 약관 단계 분리를 따른다. 최종 가입은 AUTH-011 / #41에서 처리한다.

## API 계약

`POST /api/v1/auth/login/google` · `Content-Type: application/json`

- 본문은 `{ "idToken": "<Google ID Token>" }`이다. Google Identity Services 콜백의 ID Token을 사용하며, 이메일·이름을 클라이언트가 따로 지정해 인증하지 않는다.
- `GET /api/v1/auth/csrf` 응답의 마스킹 token을 `X-CSRF-TOKEN` 헤더로 보내고 두 요청 모두 credentials를 포함한다. 만료된 Bearer 헤더는 Google 인증 시작을 막지 않는다.
- 기존 회원은 200과 `status=LOGIN`, `accessToken/tokenType/expiresIn/sessionExpiresIn`을 받는다. Refresh는 공통 HttpOnly 쿠키로만 발급한다.
- 신규 회원은 200과 `status=SIGNUP_REQUIRED`, `signupToken/signupExpiresIn`만 받는다. 회원·Google 연결·약관 동의를 저장하거나 로그인 쿠키를 발급하지 않는다. 프론트는 약관 단계로 이동해 AUTH-011에 증명을 제출한다.
- 성공 응답은 no-store다. 가입용 증명은 보호 API·갱신의 로그인 토큰을 대신할 수 없으며 원문을 DB·로그·toString에 보관하지 않는다.

- Google 로그인도 `AuthController`·`AuthControllerDocs`에서 공통 로그인·갱신·로그아웃과 함께 관리한다. 공개 경로와 응답 형식은 유지한다.

## 검증·저장

- 공개키 출처는 서버가 고정한 Google JWKS다. RS256 서명·issuer·audience·azp·만료·발급 시각·필수 프로필과 email_verified를 검사한다. 검증 키 조회는 3초 연결·읽기 제한을 사용하고 DB 트랜잭션 밖에서 수행한다.
- 기존 회원은 Google sub의 인증 연결로 조회한다. 공통 세션 발급은 Access를 개별 저장하지 않고 JWT·회원 상태·세션 폐기 여부를 확인하는 방식을 재사용한다. Google 이메일·이름 변경으로 다른 계정에 연결하거나 회원 프로필을 자동 덮어쓰지 않는다.
- 일반 식별자·이메일 조회와 회원·인증 연결의 잠금은 JPA를 사용한다. Google 식별자 잠금과 가입 증명 교체·정리 SQL은 `GoogleIdentityRepository`로 분리한다. Google sub 잠금 뒤 회원→인증 연결을 잠그고 활성·미탈퇴·GOOGLE·연결 유지 상태를 재확인한 뒤 공통 세션을 발급한다.
- 신규 사용자는 Google sub→이메일 순서로 잠그고 가입 방식에 관계없는 이메일 중복을 다시 확인한다. 이메일 비교는 DB의 IgnoreCase 기준을 유지한다.
- 신규 증명은 독립 난수 32바이트의 URL-safe Base64에 gs_ 접두어를 붙인다. 용도별 해시·sub·검증된 이메일/이름·만료만 저장하며 같은 sub의 새 증명 발급은 이전 증명을 교체한다. 저장 실패 시 함께 롤백한다.
- 로그인 요청 제한은 공통 이메일별 제한을 재사용한다. 회원가입·메일 발송 제한과 구분한다.

## 오류와 검토할 정책

| 응답 | 조건 |
|---|---|
| 400 COMMON | 필수 입력·JSON·길이 오류 |
| 401 AUTH-0024 | Google 서명·claim·용도 검증 실패, 다른 Google 식별자의 이메일 충돌 |
| 403 AUTH-0016 | 제한·탈퇴·연결 해제·가입 방식 불일치 |
| 403 AUTH-0028 | Gmail·검증된 Workspace 외 이메일의 신규 가입 제한 |
| 403 AUTH-0018 | CSRF 검증 실패 |
| 409 AUTH-0027 | 동일 이메일의 LOCAL 계정. 이메일 로그인 안내 |
| 429 AUTH-0020 | 공통 로그인 요청 제한 |
| 503 AUTH-0025/0026/0019 | Client ID 미준비 / 공개키 확인 장애 / 인증 저장소 장애 |

- 제3자 이메일의 신규 가입 제한과 가입 증명 600초는 기존 구현안이며 팀 확정 정책으로 취급하지 않는다. 추가 이메일 인증은 이번 PR에 넣지 않는다. 관련 지원 범위를 바꾸려면 프론트·신규 가입 계약을 함께 검토한다.
- 계정 연결·비밀번호 재설정·장기 로그인 유지는 MVP 범위에 추가하지 않는다. 약관 실제 원문은 프론트가 관리하며 서버 약관 버전 비교 차단을 재도입하지 않는다.

## 환경변수·배포

- 개발 배포는 #66의 단일 SSM SecureString 환경파일을 사용한다. /bareum/dev/server/env에 GOOGLE_CLIENT_IDS를 추가하고 프론트 웹 Client ID와 일치시킨다. 비어 있으면 Google 인증 요청에 503을 반환한다.
- GOOGLE_SIGNUP_TTL_SECONDS의 기본값은 600초다. 별도 값이 필요할 때 같은 환경파일에 추가한다.
- 기존 공통 로그인·CORS·쿠키 설정을 재사용한다. 실제 환경 등록은 인프라 담당자가 확인한다.
- Google 가입 증명 테이블은 배포된 보고서 마이그레이션 이후의 V2026.11.09.15.20__create_google_signup_proof.sql에서 생성한다.

## 검증과 적용 조건

- Google 토큰 서명·claim, 기존 로그인·가입 증명 분기, CSRF·동시 요청·저장 실패와 기존 인증의 회귀를 검증한다.
- 최신 develop 스키마에 신규 마이그레이션 하나를 적용하고 기존 회원·세션·소비 토큰 이력 보존과 재실행 시 추가 적용이 없는지 확인한다.
- 실제 Google 계정과 브라우저 연동은 개발 환경 설정·배포 후 확인한다.
