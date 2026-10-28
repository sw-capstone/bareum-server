# 이메일 로그인·공통 인증 적용 기준

- 대상: AUTH-002, GitHub #24 / #37
- 상태: 구현안·팀 검토 필요
- 기준: #32 병합 및 #33 회원가입 코드를 반영한다. #33 병합 후 최신 develop과 다시 대조한다.

## API와 인증 흐름

- `POST /api/v1/auth/login/email`: email·password를 검증한다. 이메일은 DB의 대소문자 무시 조회를 사용하고 비밀번호 원문을 변형하지 않는다. 가입된 비밀번호에 신규 가입 정책을 다시 적용하지 않는다.
- 성공은 200과 `accessToken/tokenType/expiresIn/sessionExpiresIn`이다. Access는 Bearer 헤더로 전달하며 프론트 메모리에 보관한다. Refresh는 `bareum_refresh` HttpOnly·Secure·SameSite=None·호스트 전용·Path=/api/v1/auth 쿠키로 전달한다. 응답은 no-store다.
- 기존 협의 기준인 Access 최대 15분·최초 로그인부터 세션 최대 8시간을 유지한다. 기본 로그인과 장기 로그인 유지 옵션은 구분하며 `keepSignedIn=true`는 지원하지 않는다.
- `GET /api/v1/auth/me`: 유효한 Access와 활성 회원 상태를 확인해 `memberId/name/email`을 반환한다. 다른 도메인의 리소스 소유권 검증은 각 도메인에서 수행한다.
- Google 전용 계정에는 Google 로그인을 안내한다. 없는 계정과 비밀번호 불일치는 동일한 401로 응답한다. LOCAL 계정의 제한 상태는 비밀번호 확인 후 구분한다.

## 저장·동시성·공통 보안

- 비밀번호 확인 후 회원 행을 잠그고 계정 상태와 해시를 재확인한 뒤 세션을 저장한다. 도중에 비밀번호·상태가 바뀌거나 저장에 실패하면 로그인 정보를 발급하지 않는다.
- 재로그인은 현재 Refresh를 교체하며, 이전 Access는 원래 만료까지 허용한다. 토큰 원문은 DB에 저장하지 않고 용도별 해시·회원·family·만료·폐기 시각을 저장한다.
- 일반 인증 조회는 조회 전용 JPA 매핑·JPQL로 분리한다. 회원 잠금·세션 교체·Access 등록·정리의 원자적 SQL은 유지한다.
- JWT는 HS256 서명과 issuer·audience·sub·jti·iat·exp·용도를 확인한다. Google Client ID와 서버 JWT 서명 키는 별개다.
- 기존 공개 인증 API·약관 조회·Scalar·서버 health는 공개 경로를 유지한다. 보호 API의 미인증·접근 거부도 기존 ProblemDetail 규격을 사용한다.
- CORS는 설정한 정확한 Origin과 credentials만 허용한다. 쿠키를 소비하는 후속 갱신·로그아웃에는 CSRF 보호를 유지한다. 갱신·로그아웃 API 자체는 후속 PR 범위다.
- 회원가입 코드·메일 Outbox·통합 인증 컨트롤러·확인 정책·SSM 11개 주입은 선행 코드 그대로 유지한다. 약관 버전 비교·관련 503을 추가하지 않는다.

## 설정과 배포 준비

| 변수 | 기본값 | 용도 |
|---|---|---|
| LOGIN_JWT_SIGNING_KEY | 없음 | 최소 32바이트 독립 난수의 Base64 값. 누락·오류 시 인증 정보 발급·검증 차단 |
| LOGIN_ALLOWED_ORIGINS | 없음 | 운영 bareum.site와 develop Vercel 주소의 정확한 Origin 목록 |
| LOGIN_ACCESS_TTL_SECONDS | 900 | Access 수명, 최대 900초 |
| LOGIN_SESSION_TTL_SECONDS | 28800 | 최초 로그인부터 세션 수명, 최대 28800초 |
| LOGIN_COOKIE_SECURE | true | Refresh·CSRF 쿠키 Secure 속성 |
| LOGIN_COOKIE_SAME_SITE | None | None은 Secure 필수 |
| LOGIN_MAX_REQUESTS_PER_WINDOW | 10 | 이메일별 로그인 요청 수 |
| LOGIN_REQUEST_WINDOW_SECONDS | 60 | 요청 집계 창(초) |

운영 Origin은 `https://bareum.site`, develop은 `https://bareum-web-git-develop-numkites-projects.vercel.app`이다. 실제 환경변수 준비는 배포 환경파일 관리 변경 #63과 맞추고, AWS 적용은 인프라 담당자가 진행한다. env v2의 기존 메일·확인 설정을 유지한다. 기능 코드 준비와 배포 설정 완료를 구분하며 필요한 값이 준비되기 전 병합하지 않는다.

기존 develop에 반영된 마이그레이션은 변경하지 않는다. 신규 로그인 저장 구조는 기존 준비 마이그레이션을 유지하며 병합 시 적용 순서를 다시 확인한다.

## 임시 기준과 재검토 조건

| 쟁점 | 구현 기준·영향 | 재검토 조건 |
|---|---|---|
| 로그인 요청 제한 | DB 이메일 비교값의 해시로 성공·실패 합산 60초당 10회. 실패 요청도 별도 트랜잭션으로 기록. IP·전체 서비스 제한을 대신하지 않음 | 남용 방지·게이트웨이 정책 확정 시 |
| 쿠키 연동 | Secure·SameSite=None 유지. Vercel에서는 타사 쿠키 제한 가능 | 두 프론트 주소의 실제 브라우저 통합 검증 시 |
| 장기 로그인 유지·계정 연결 | 정책 표와 화면 범위가 상충하므로 이번 기능에 추가하지 않음 | 팀이 도입 범위·기간을 확정할 때 |
| 데이터 정리 | 만료 Access와 24시간 지난 로그인 요청 창을 매시간 삭제 | 운영 보존 정책 확정 시 |
| 비밀번호 입력 상한 | 기존 계정과의 호환을 위해 새 상한을 정하지 않음 | 가입·로그인·재설정의 입력·HTTP 크기 정책을 함께 확정할 때 |

## 검증 범위

- Java 25·PostgreSQL 17에서 `check bootJar --no-daemon`: 전체 417개 테스트 통과, 실패·오류·건너뜀 0개. Spotless·Flyway 중복 검사·JAR 빌드 성공.
- 회원가입→로그인→내 정보 조회, 기존 공개 API·health 접근, 두 프론트 Origin의 credentials 허용, 잘못된 자격증명·제한 계정·토큰 만료·동시 발급·저장 실패 롤백을 검증했다.
- 저장소 정적 검사 통과. PostgreSQL 18·ARM64 이미지 CI는 기존 PR 재개 후 확인한다. 실제 배포·Google 계정·브라우저 통합은 미검증이다.
