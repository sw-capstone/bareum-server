# Auth·Term 명세·리뷰 기준 정비

- 기준일: 2026-10-13
- 관련 작업: [GitHub #22](https://github.com/sw-capstone/bareum-server/issues/22), BRM-25
- 상태: 구현 대조 결과·팀 미확정 정책은 별도 검토
- 소유 역할: 백엔드 인증 담당·프론트 인증 화면 담당

## 적용 근거와 충돌 처리

| 항목 | 기준·현재 동작 | 차이와 처리 |
|---|---|---|
| 비밀번호 | [최신 기술 정책](https://app.notion.com/p/3e311880adea8077a667c9a8eae3d107)과 [화면 기획](https://app.notion.com/p/8db11880adea821da91f01c46e4d3804)의 최소 12자·영문·숫자·허용 특수문자·공백 금지 | [AUTH-001](https://app.notion.com/p/6ce11880adea82f38f3401e7015c793d)의 이전 8~12자 문구와 구분한다. 가입·재설정은 같은 정책을 사용하며 로그인 입력은 변환하지 않는다. |
| 비밀번호 해시 | 기존 서비스 명세·개발 원칙의 Argon2id | 새 가입·재설정은 Argon2id로 저장한다. 이전 PBKDF2 저장값은 검증 호환만 제공하고 새 PBKDF2 저장값을 생성하지 않는다. |
| 토큰·재로그인 | 기존 JWT Access·Refresh와 Rotation, #7의 회원당 단일 Refresh·이전 Access 만료까지 허용 | JWT 서명·용도·만료를 검증하고 Access 해시 등록을 분리한다. 재로그인·갱신 후 이전 Access의 만료를 유지하며, 로그아웃·재사용 탐지·재설정은 정해진 범위의 Access를 폐기한다. |
| 로그인·유지 | 프론트 합의에 따라 Access 15분·최초 로그인부터 최대 8시간, 메모리 Access와 HttpOnly 갱신 쿠키, /me 조회 | AUTH-002의 선택형 로그인 유지 문구와 MVP 제외 표를 구분한다. keepSignedIn=true는 거부하며 장기 로그인 구현을 추가하지 않는다. 쿠키·단일 세션 세부 기준은 [로그인 문서](login-authentication-draft.md)의 검토 항목을 유지한다. |
| 이메일 비교·DB 제약 | [#18 리뷰와 답변](https://github.com/sw-capstone/bareum-server/pull/18#discussion_r4222546461)에 맞춰 IgnoreCase 조회·upper(email) 잠금·유일 인덱스 | LOCAL·GOOGLE 모두 주소 중복을 차단한다. 탈퇴 완료 계정의 즉시 이메일 재사용은 기존 팀 미확정 임시 기준을 유지하며 Google 식별자 재사용 정책으로 확대하지 않는다. |
| 재설정 발송 대상 | [AUTH-010](https://app.notion.com/p/64b11880adea8353b42681be66aab506)의 이메일 로그인 가능한 계정과 최종 재설정의 ACTIVE·미탈퇴·LOCAL 조건 | 최초·재발송과 worker의 수신자 검사에도 같은 조건을 적용한다. 제한된 계정은 등록 주소로도 발송하지 않고, 접수 후 제한된 대기 작업은 CANCELLED·암호문 삭제로 종료한다. 신규 오류 코드를 만들지 않고 AUTH-0003을 사용한다. |
| 수신 주소 | [등록 주소 발송 리뷰와 답변](https://github.com/sw-capstone/bareum-server/pull/18#discussion_r4222588985) | PASSWORD_RESET의 SMTP 수신자·인증 요청·발송 이력은 회원 등록 주소를 유지한다. 요청 주소를 발송 주소로 되돌리지 않는다. |
| Outbox·타임아웃·설정 | [Outbox 리뷰와 답변](https://github.com/sw-capstone/bareum-server/pull/18#discussion_r4226356881)의 접수 커밋 후 별도 worker 발송·대기 0초·SMTP 수락 후 만료 시작 | 연결·읽기·쓰기 기본 5초, 생성자 바인딩의 불변 설정을 유지한다. 같은 작업·코드로 재시도하며 SMTP와 DB 사이의 중복 전달 가능성도 유지한다. 사용하지 않는 이전 동기 발송 응답 DTO는 제거한다. |
| 약관 | 프론트 고정 원문과 agreements의 네 boolean, 서버의 승인 ID·버전·동의 이력 연결 | [#9 리뷰와 답변](https://github.com/sw-capstone/bareum-server/pull/9#discussion_r4151949145)대로 공개 목록은 findAll·순서 미보장이다. 가입용 잠금 조회의 ID 정렬은 화면 표시 순서가 아니라 잠금 순서다. 운영 원문·승인 버전은 임의 생성하지 않는다. |
| 검증 범위 | 서버 CI의 Gradle·PostgreSQL·ARM64·Compose 검사와 별도 하네스·비밀정보 검사 | README·개발 가이드의 과거 하네스 전용 CI 문구를 현재 workflow에 맞춘다. 실제 Google 계정·브라우저 연동을 CI 성공으로 간주하지 않는다. |

## 미확정 사항과 영향

- 운영 메일 제공자·발신 계정, 발송 간격·횟수, 인증 실패 초기화·복구와 재설정 유효기간은 기존 미설정 차단을 유지한다. 정책 승인 시 설정·경계 테스트·프론트 안내를 함께 반영한다.
- 약관 원문·승인 버전, Google Client ID·승인 Origin·지원 계정, 실제 HTTPS 서버·CORS·타사 쿠키 조건은 실제 연동 전 확인한다.
- 비밀번호 찾기는 최신 MVP 표의 제외·3순위 기준을 유지한다. 구현과 배포 포함 승인은 구분하며 기존 세션 폐기 범위도 팀 검토 대상으로 유지한다.
- [#20의 선행 PR 리뷰](https://github.com/sw-capstone/bareum-server/pull/20#issuecomment-6018840580)에 따라 #18 병합 후 최신 develop을 반영하고 AUTH-006 변경 범위를 정리한다. 후속 통합 코드가 있다는 이유로 선행 PR 승인을 대신하지 않는다.

## 검증

- Auth·Term 통합 정비 기준 `./gradlew check bootJar --no-daemon`: 전체 684개 통과, 실패·오류·건너뜀 0개, JAR 빌드 성공.
- 추가 회귀 사례 8개로 제한·탈퇴 표시 계정의 접수 거부, 접수 후 정지 계정의 SMTP 미호출·CANCELLED·암호문 삭제, 재발송 거부 시 기존 코드 보존을 확인했다.
- 하네스 정적 검사 4개 통과, 실패 0개·해당 없음 3개. API 15개 경로와 JSON 예시 14개를 대조했다.
- 실제 Google 계정·프론트 브라우저 연동은 이 검사에 포함하지 않는다.
 API 주소·응답 예시와 실제 연동 전 확인 사항은 [Auth·Term 연동 안내](../integration/auth-term.md)를 따른다.
