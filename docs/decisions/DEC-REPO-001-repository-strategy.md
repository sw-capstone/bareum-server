# DEC-REPO-001 저장소 구성 방식

- 상태: 결정
- 로컬 반영일: 2026-09-17
- 적용 상태: 프론트엔드·백엔드·AI 세 저장소 분리 적용
- 소유 역할: `project-team`
- 결정: 멀티레포를 사용하며 제품 코드를 프론트엔드, 백엔드, AI로 분리한다.
- 저장소 구성: `sw-capstone/bareum-web`, `sw-capstone/bareum-server`, `sw-capstone/bareum-server-ai`
- 책임: `bareum-web`은 화면과 공개 API 소비, `bareum-server`는 백엔드 API와 서버 경계, `bareum-server-ai`는 AI 처리 영역을 담당한다.
- 계약·하네스: 공통 계약은 형식이 확정되면 `bareum-server/packages/contracts`에 추가한다. 서버 하네스는 `bareum-server/harness`, AI 기본 정적 하네스는 `bareum-server-ai/harness`에서 각각 관리한다. 제품 AI 기능·평가 검사는 관련 구현과 기준이 준비된 작업에서 AI 저장소에 추가한다.
- 이유: 백엔드와 AI의 작업량·변경 주기·책임을 독립적으로 관리하고, 저장소별 검사와 리뷰 범위를 명확히 하기 위해서다.
- 영향: 저장소 간 형식은 계약으로 연결한다. 저장소를 분리해도 API, AI 인터페이스와 통합 검토가 자동으로 해결되는 것은 아니다.
- 전환 순서: `bareum-server-ai` 저장소 경계 생성 → AI 구현·검사 기준 확정 → 계약과 서버·웹·AI 연동 검토
- 재검토 조건: 저장소 간 변경 조정 비용, 서버 하네스 중복, 계약 배포 방식이 병목으로 확인될 때 구조를 재검토한다.
