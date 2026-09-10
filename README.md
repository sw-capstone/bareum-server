# bareum-server — 백엔드·AI·하네스 레포

`sw-capstone/bareum-server`는 공개 API, 세션·분석 작업·영속성, 내부 AI 파이프라인과 하네스를 관리한다. 프론트엔드는 별도 `bareum-web` 레포에서 공개 계약을 소비한다.

최신 하네스 구조 문서는 [`docs/harness/harness-v1.2.md`](docs/harness/harness-v1.2.md)다.

## 저장소 지도

```text
apps/           api, ai_worker, training 실행 단위
docs/architecture/backend/ API, 세션, 분석 작업, 저장·삭제 책임 문서
docs/architecture/ai/       파싱, 구조·규칙 검사, 검색, 판정, 수정안 책임 문서
packages/contracts/ API·AI 공유 계약(계약 확정 시 추가)
rules/          유형별 제품 규칙
prompts/        계획/결과보고서용 프롬프트
data/reference/ 기준 데이터와 snapshot
evals/          평가셋·fixture·결과
harness/        개발 과정 하네스와 제품 AI 하네스
infra/          로컬 실행·배포·관측 설정
tests/          계약·통합·불변식 테스트
scripts/        로컬과 CI의 공통 실행 진입점
docs/           기획 요약·아키텍처·결정·실험 기록
```

책임·계약 위치·ID·검증 진입점을 공통 기준으로 사용한다. 모델, 프레임워크, 세부 수치와 팀 역할은 기획문서의 확정 상태와 팀 결정에 따라 반영한다.

작업 절차는 [`docs/guide/development-guide.md`](docs/guide/development-guide.md), 하네스의 적용 범위와 확장 기준은 [`docs/harness/README.md`](docs/harness/README.md)에서 확인한다.

계약이 확정되면 프론트엔드 소비자 검증과 동기화 방식을 [`DEC-REPO-001`](docs/decisions/DEC-REPO-001-repository-strategy.md)에 추가한다.

회의용 하네스 질문 목록은 [`docs/guide/harness-discussion-questions.md`](docs/guide/harness-discussion-questions.md)에서 확인한다.

```bash
./scripts/run-harness.sh check
./scripts/test-harness.sh
```
