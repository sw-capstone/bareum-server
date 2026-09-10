# 영역 구조와 의존성 경계

멀티레포의 제품 코드는 프론트엔드와 백엔드로 나눈다. 실제 GitHub 저장소는 `bareum-web`과 `bareum-server` 두 개이며, AI·공유 계약·하네스는 `bareum-server` 내부에서 관리한다.

```text
bareum-server/packages/contracts ──> bareum-server ── 공개 API ──> bareum-web
                                  │
                                  └── harness · evals · integration Smoke

bareum-server 내부 AI 모듈
  ├─ parser
  ├─ RMA + rule routing
  ├─ deterministic checks
  ├─ retrieval + judgment
  └─ revision + validation

rules · prompts · data/reference → ai
evals → product harness → 품질·재현성 증거
development harness → 계약·ID·테스트·CI 증거
```

제품 저장소는 bareum-web과 bareum-server로 구성한다. bareum-server는 서버 오케스트레이션과 AI 파이프라인을 함께 소유하되, 두 책임의 모듈·테스트 경계는 유지한다. 계약은 `bareum-server/packages/contracts/`에서 관리하고, 계약 정의·API와 Worker 구현·bareum-web 소비·통합 테스트 순서로 진행한다. 제품 AI는 결정론 검사와 모델 판정을 분리하며, 근거 검증을 통과한 결과만 공개 계약으로 전달한다.

하나의 서버 레포는 API와 AI를 같은 프로세스로 묶는다는 뜻이 아니다. API와 AI Worker는 별도 패키지·이미지·프로세스로 분리할 수 있으며, 백엔드는 AI의 구체 통신 방식이 아니라 `AnalysisEngine` Port와 공유 계약에 의존한다. 큐·통신 방식·물리 배포 환경은 결정 기록으로 관리한다.
