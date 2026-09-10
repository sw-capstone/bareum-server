# bareum-server — 백엔드·AI 서비스 레포

`sw-capstone/bareum-server`는 바름 서비스의 백엔드와 AI 파이프라인을 함께 관리하는 저장소다. 공개 API, 세션·분석 작업·영속성, 문서 처리와 AI 판정 흐름을 담당하며, 프론트엔드는 별도 `bareum-web` 레포에서 공개 계약을 소비한다.

## 담당 범위

- `apps/api/`: 세션, 분석 작업, 영속성, 공개 API 오케스트레이션
- `apps/ai_worker/`: 문서 파싱, 구조·규칙 검사, 검색, 판정, 수정안 파이프라인
- `packages/contracts/`: 서버와 프론트엔드가 공유하는 API·AI 계약
- `tests/`: 계약·통합·불변식 검증
- `harness/`: 저장소 규칙, 계약, 근거, AI 안전성·회귀 검사를 실행하는 검증 체계

서버와 AI 영역의 책임은 분리해 관리하되, 하나의 서버 레포에서 함께 개발한다. 영역 간 형식은 계약으로 연결하고, 근거가 검증되지 않은 결과는 확정 판정으로 노출하지 않는다.

## 저장소 구조

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

각 디렉터리의 세부 책임과 경계는 [`docs/architecture/repository-structure.md`](docs/architecture/repository-structure.md)와 [`docs/architecture/repository-boundaries.md`](docs/architecture/repository-boundaries.md)를 기준으로 한다. 모델, 프레임워크, 세부 수치와 팀 역할은 확정된 기획문서와 팀 결정에 따라 반영한다.

## 문서 안내

- 백엔드 구조: [`docs/architecture/backend-architecture.md`](docs/architecture/backend-architecture.md)
- AI 구조: [`docs/architecture/ai/README.md`](docs/architecture/ai/README.md)
- 계약·레포 경계: [`docs/architecture/repository-boundaries.md`](docs/architecture/repository-boundaries.md)
- 개발 절차: [`docs/guide/development-guide.md`](docs/guide/development-guide.md)
- 제품 기획·서비스 명세: [`docs/product/README.md`](docs/product/README.md)
- GitHub 운영 규칙: [`docs/decisions/DEC-GIT-001-github-flow.md`](docs/decisions/DEC-GIT-001-github-flow.md)

### 하네스 검증

하네스는 백엔드·AI 구현을 대신하는 기능이 아니라, 계약·규칙·근거·AI 안전성·성능 회귀를 검증하는 저장소 품질 체계다. 적용 범위와 확장 기준은 [`docs/harness/harness-v1.2.md`](docs/harness/harness-v1.2.md), 파일별 역할은 [`docs/harness/README.md`](docs/harness/README.md)에서 확인한다.

```bash
./scripts/run-harness.sh check
./scripts/test-harness.sh
```
