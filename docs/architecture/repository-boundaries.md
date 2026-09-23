# 영역 구조와 의존성 경계

멀티레포의 제품 코드는 프론트엔드, 백엔드, AI로 나눈다. 목표 저장소 구성은 `bareum-web`, `bareum-server`, `bareum-server-ai` 세 개다. 서버 하네스는 현재 `bareum-server`에서 관리한다.

```text
bareum-web ← 공개 API 계약 → bareum-server ← AI 인터페이스 계약 → bareum-server-ai

웹 CI: 웹 내부 검사
서버 CI: 서버 내부 검사
AI CI: AI 저장소 내부 정적 검사
```

위 그림은 책임과 연동 경계이며 실제 API·AI 연결이 완료됐다는 의미는 아니다. `bareum-server`는 백엔드 공개 API를, `bareum-server-ai`는 AI 처리를 관리한다. 계약은 형식이 확정되면 `bareum-server/packages/contracts/`에 추가한다. 웹↔서버 또는 서버↔AI 중 영향받는 제공자·소비자의 구현과 테스트를 함께 검토한다.

저장소를 분리한다는 경계만 확정되어 있다. API와 AI의 코드 경로, 프로세스·패키지·이미지 분리 여부, 큐·통신 방식·물리 배포 환경은 결정 기록이 생긴 뒤 필요한 경로와 함께 추가한다.
