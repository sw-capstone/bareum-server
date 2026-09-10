# 레포 구조와 실행 단위

## 현재 레포 결정

제품 코드는 멀티레포로 운영한다.

| 레포 | 책임 |
| --- | --- |
| `sw-capstone/bareum-web` | 화면과 공개 API 계약 소비 |
| `sw-capstone/bareum-server` | 백엔드 API, AI 파이프라인, 공유 계약, 평가와 하네스 |

백엔드와 AI를 별도 Git 저장소로 나누지 않는다. 다만 같은 저장소에 있다고 해서 같은 프로세스나 컨테이너에서 실행해야 하는 것은 아니다.

## 현재 서버 레포의 논리 구조

```text
bareum-server/
├── apps/          # 실행 단위: api, ai_worker, training
├── packages/contracts/ # 계약 확정 시 추가할 공유 계약 위치
├── docs/architecture/backend/ # 백엔드 책임과 기능 문서
├── docs/architecture/ai/      # AI 파이프라인 책임과 모듈 문서
├── rules/
├── prompts/
├── data/reference/
├── evals/
├── harness/
├── infra/
├── tests/
├── scripts/
└── docs/
```

실행 코드는 `apps/` 아래에 두고, 백엔드와 AI 책임 문서는 `docs/architecture/` 아래에 둔다. API와 AI는 하나의 서버 레포에서 관리하되 실행 단위와 의존성은 분리한다.

## 실행 단위와 구현 목표

백엔드와 AI Worker를 실제로 실행할 때는 다음 책임을 별도 패키지·이미지·프로세스로 분리한다.

```text
bareum-server/
├── apps/
│   ├── api/          # FastAPI 앱, 기능 모듈, API 이미지
│   ├── ai_worker/    # 파싱·임베딩·검색·추론, Worker 이미지
│   └── training/     # 모델 학습·평가 실행 단위
├── compose.yaml      # 로컬 통합 실행
└── ...               # rules, prompts, evals, harness, docs
```

`apps/api`, `apps/ai_worker`, `apps/training`은 각각 API, AI Worker, 학습·평가를 실행하는 단위다. 각 실행 단위의 프로젝트 설정과 컨테이너 설정은 해당 구현과 함께 관리한다. 공유 계약이 확정되면 `packages/contracts/`를 생성하고 각 실행 단위에서 참조한다.

- 학습·평가 코드를 `apps/training/`에 어떤 범위로 둘지
- Python 버전·의존성·lockfile을 앱별로 둘지 workspace로 묶을지
- `compose.yaml`과 각 Dockerfile은 로컬 통합 실행과 배포 설정에 맞춰 관리한다.

이동 전까지 문서와 하네스는 현재 경로를 기준으로 실행한다. 계약이 추가된 뒤 경로 이동이 발생하면 하네스 정책, 링크, CI와 프론트 소비자 검증을 같은 변경에서 갱신한다.

## 격리 규칙

- API와 AI Worker는 독립적인 실행 명령과 의존성 경계를 가진다.
- API 이미지에는 Worker 전용 무거운 의존성을 넣지 않는다.
- Worker는 API Router·Repository 내부 구현을 직접 import하지 않고 계약과 명시적으로 공유하기로 한 도메인 패키지만 사용한다.
- 작업 메시지는 `schema_version`, `job_id`, `trace_id`를 보존한다.
- `packages/contracts/`가 추가되거나 변경되면 API·Worker·프론트 소비자 검사를 함께 실행한다.
- 배포 시 API와 Worker 이미지는 각각 빌드·롤백할 수 있어야 한다.

## 관련 문서

- [`repository-boundaries.md`](repository-boundaries.md)
- [`backend-architecture.md`](backend-architecture.md)
- [`infra-boundaries.md`](infra-boundaries.md)
- [`DEC-REPO-001`](../decisions/DEC-REPO-001-repository-strategy.md)
