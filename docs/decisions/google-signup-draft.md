# Google 가입 완료 적용 기준

- 대상: AUTH-011 · BRM-33 · GitHub #30 / #41
- 상태: 구현안·팀 검토 필요
- 기준: [AUTH-011](https://app.notion.com/p/24511880adea833e8870018de01d7df3), AUTH-004와 최신 기술·정책 명세. 기존 계정 연결·비밀번호 재설정·장기 로그인 유지는 MVP에 추가하지 않는다.

## API와 처리 결과

`POST /api/v1/auth/signup/google` · `Content-Type: application/json`

- 본문은 AUTH-004의 signupToken과 agreements(age/service/privacy/marketing)다. 검증된 증명의 이메일·이름·Google sub만 사용하며 클라이언트 프로필을 따로 받지 않는다.
- Refresh 기반 API와 같은 CSRF 쿠키·마스킹 X-CSRF-TOKEN 헤더와 credentials를 사용한다. 기존 만료 Bearer는 가입을 막거나 CSRF를 대신하지 않는다.
- 성공은 201과 공통 accessToken/tokenType/expiresIn/sessionExpiresIn이다. Refresh는 HttpOnly 쿠키로만 전달하고 no-store로 응답한다. 프론트는 가입 완료 후 홈으로 이동한다.
- 가입 증명은 일반 API 인증·갱신에 사용할 수 없으며 최초 성공 후 재사용할 수 없다.

## 동의·저장·동시성

- 필수 age/service/privacy 미동의 또는 네 항목 응답 누락은 가입을 거부한다. marketing=false도 가입되며 선택 응답과 동의·거부 시각을 기록한다.
- 약관 실제 원문은 프론트가 관리한다. 서버는 동의 이력을 연결할 네 약관의 임시 식별 데이터를 사용하며 원문 확정을 기다리지 않는다. 버전 유효성·형식·적용 버전 비교와 관련 503을 추가하지 않는다.
- 가입 증명의 발급·조회·잠금·소비는 GoogleIdentityRepository에서 JDBC로 처리한다. 기존 회원과 Google 연결은 JPA로 조회하며, 가입 시 생성한 회원 행과 세션은 같은 트랜잭션에서 저장한다.
- Google sub→증명 행→이메일 순서로 잠그고 잠금 후 만료·소비 여부를 다시 확인한다. 중복 이메일은 LOCAL·GOOGLE 모두 차단하고 자동 계정 연결은 하지 않는다.
- 회원·Google 연결·네 약관 응답·증명 소비·자동 로그인 세션을 한 트랜잭션으로 저장한다. Access는 개별 저장하지 않고 공통 JWT·회원 상태·세션 폐기 검증을 재사용한다. 공통 AuthController와 공통 Clock을 사용한다. 도중에 증명이 만료되거나 DB 저장·커밋이 실패하면 전부 롤백한다.
- 같은 증명의 동시 가입, 서로 다른 sub의 동일 이메일, 이메일 가입과의 경쟁에서 회원은 하나만 생성한다. Google 로그인에서 증명을 교체할 때와 같은 sub 락을 먼저 잡는다.
- 기본 트랜잭션 격리를 사용하며 PostgreSQL 기본값인 READ COMMITTED를 불필요하게 중복 지정하지 않는다.

| 응답 | 조건 |
|---|---|
| 400 COMMON | JSON·필수 입력·증명 형식·약관 Boolean 누락 |
| 400 TERM | 필수 약관 미동의 |
| 401 AUTH-0029 | 미등록·만료·소비·교체된 가입 증명 |
| 409 AUTH-0027 / AUTH-0001 | 기존 LOCAL / 다른 회원 이메일 중복 |
| 403 AUTH-0018 | CSRF 검증 실패 |
| 500 TERM-0004 | 이력을 저장할 DB 약관 데이터 누락·중복 등 데이터 오류 |
| 503 AUTH-0030 | 가입 저장·커밋 실패 |

## 검증과 적용 조건

- 신규 환경변수·SQL·의존성은 추가하지 않고 #40의 가입 증명과 공통 인증 설정을 재사용한다.
- 필수 동의·선택 거부, 증명 재사용·만료, 중복·동시 가입, 저장 실패 롤백, 가입 후 갱신·로그아웃과 OpenAPI를 검증한다.
- 브라우저의 실제 Google 계정·프론트 연동, AWS 반영과 배포 서버 동작은 별도로 검증한다.
