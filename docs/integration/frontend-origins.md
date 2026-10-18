# 프론트 도메인과 Google 연동 준비

## 확정 주소

| 용도 | Origin |
| --- | --- |
| 프론트 운영 | `https://bareum.site` |
| 프론트 develop 테스트 | `https://bareum-web-git-develop-numkites-projects.vercel.app` |
| 백엔드 API | `https://api.bareum.site` |

백엔드 CORS와 향후 Google 승인된 JavaScript 원본에는 두 프론트 Origin을 정확히 사용한다. 경로·후행 슬래시·와일드카드·임의 Preview 주소는 추가하지 않는다. API 호스트를 프론트 Origin 대신 등록하지 않는다.

## 백엔드 설정

기존 `LOGIN_ALLOWED_ORIGINS`의 쉼표 구분 목록을 사용한다. Java 코드에 도메인을 하드코딩하지 않는다.

```dotenv
LOGIN_ALLOWED_ORIGINS=https://bareum.site,https://bareum-web-git-develop-numkites-projects.vercel.app
```

개발 서버 Parameter Store의 `/bareum/dev/server/LOGIN_ALLOWED_ORIGINS`에 위 값을 String으로 등록한다. 기존 환경 주입 경로·CORS 필터는 두 주소를 지원하며 credentials를 허용한다. `GOOGLE_CLIENT_IDS`가 없어도 CORS 설정을 준비할 수 있고, Google 인증은 기존대로 미설정 차단을 유지한다.

설정 예시·테스트 추가는 실제 AWS 등록·배포를 의미하지 않는다. 현재 develop에는 후속 로그인 보안 설정과 Google API가 병합되지 않았으므로, 파라미터만 등록해서 현재 배포에 CORS가 활성화된다고 판단하지 않는다. 인증 기능·배포 주입 경로가 병합된 이미지에 필요한 설정을 준비해 적용하고 실제 Origin별 응답을 확인한다. 준비되지 않은 상태에서 `AUTH_ENV_ENABLED`를 임의로 켜지 않는다.

## Google 준비와 반영 순서

- 웹 Client ID 생성·Google Cloud 설정·`GOOGLE_CLIENT_IDS` 실제 값 등록은 아직 미완료다. 가짜 ID를 배포 설정에 넣지 않는다.
- Client ID 준비 후 같은 ID를 프론트와 서버에 적용하고, 승인된 JavaScript 원본에 위 운영·develop 주소를 각각 등록한다. 이 문서는 Google Console 등록 완료 기록이 아니다.
- Google 로그인은 `POST /api/v1/auth/login/google`, 가입 완료는 `POST /api/v1/auth/signup/google` 계약이다.
- 현재 Google 로그인 PR #40과 가입 완료 PR #41은 닫힌 미병합 상태다. 선행 인증 변경을 순차 반영하고 기존 PR을 업데이트·재오픈하는 방식으로 리뷰한다. 병합·개발 서버 배포 시점은 아직 확정하지 않았다.
- Client ID 준비 전에도 API 계약·화면 분기·모의 검증을 진행할 수 있다. 실제 Google 계정 검증은 기능 병합·배포와 Google 설정을 완료한 뒤 수행한다.

## 검증 기준

- 두 Origin이 환경변수에서 정확한 목록으로 바인딩되고 SSM 환경 생성 결과에 그대로 보존되는지 확인한다.
- Google Client ID 없이도 위 Origin 설정으로 환경 생성이 가능하고, 임의 ID를 생성하지 않는지 확인한다.
- 두 Origin의 Google 로그인 OPTIONS 사전 요청에서 해당 Origin과 credentials 허용 응답을 확인한다. HTTP·유사 도메인·미등록 Preview 주소는 거부해야 한다.
- 실제 배포 후 운영·develop 화면의 CORS·쿠키·CSRF와 Google 기존 회원 로그인·신규 약관 동의·가입을 별도로 검증한다.

설정 주입과 적용 순서는 [Auth·Term 배포 설정](../guide/auth-term-deployment.md)을 따른다.

## 준비 변경의 검증 결과

- `./gradlew check bootJar --no-daemon`: 전체 675개 테스트 통과, 실패·오류·건너뜀 0개 및 JAR 빌드 성공. 두 Origin의 실제 HTTP 사전 요청·credentials 허용과 미등록 주소 차단을 포함한다.
- 환경 생성 모의 검사 22개 중 21개 통과, Windows의 POSIX 권한 검사 1개 제외. 두 Origin의 값 보존과 Google Client ID 없이 환경 생성 성공을 검증했다.
- 공개 프론트는 HTTP 200을 확인했다. 현재 공개 API 명세에는 Google 로그인·가입 경로가 없으며, 해당 경로의 두 Origin 사전 요청에는 CORS 허용 헤더가 없어 운영 적용 완료로 처리하지 않는다. 실제 Google 계정·AWS 설정 적용은 미검증이다.
