# 하네스 문서

하네스의 최신 구조와 책임 경계는 [`harness-v1.2.md`](./harness-v1.2.md)에 정의한다.
구현 진행에 따라 추가할 파일과 순서는 [`harness-file-roadmap.md`](./harness-file-roadmap.md)를 따른다.

저장소는 다음 순서로 운영한다.

1. 기획·회의 자료와 관련 결정 기록을 확인한다.
2. 계약·규칙·평가 자산을 `packages/contracts/`, `rules/`, `evals/`에 관리하고 하네스 검사를 연결한다.
3. `apps/api`, `apps/ai_worker`, `apps/training`, `bareum-web`은 각 책임 경계를 넘지 않게 구현한다.
4. `rules/`, `prompts/`, `data/reference/`, `evals/`는 서로 다른 버전 자산으로 관리한다.
5. `./scripts/run-harness.sh check`와 `./scripts/test-harness.sh`를 실행한다.
6. PR에 실행 결과, 미수행 검사, 호환성 영향과 남은 위험을 기록한다.

하네스 검사는 저장소 구조와 문서·계약 무결성을 정책에 정의된 범위에서 확인한다. 제품 품질과 모델 성능은 별도 평가셋과 사람 검토로 판단한다.
