# 하네스 후속 파일 추가 로드맵

문서 상태: 하네스 확장 순서 기준  
적용 문서: `harness-v1.2`  
목적: 구현 진행에 따라 필요한 하네스 파일을 적절한 시점에 추가하고, 빈 파일이나 근거 없는 프로젝트 자산이 먼저 만들어지는 일을 방지한다.

## 1. 기본 원칙

1. 실제 검사 대상이 생긴 뒤 검사 파일을 추가한다.
2. 계약·규칙·프롬프트·평가 데이터의 내용은 해당 영역 개발자가 작성하고, 하네스 담당자는 형식·호환성·회귀 검사를 작성한다.
3. 새 검사는 검사 ID, 입력, 판정 조건, 실패 메시지, 실패 재현 테스트를 함께 갖는다.
4. 파일 경로가 추가되면 `harness/policy.json`, CI, 실행 스크립트와 관련 문서를 같은 변경에서 갱신한다.
5. 실행되지 않은 검사는 통과로 기록하지 않고 `not_applicable`, `disabled`, 미수행 또는 판단보류로 구분한다.
6. README가 있는 디렉터리에 빈 디렉터리 유지를 위한 `.gitkeep`을 추가하지 않는다.
7. 하네스는 제품 코드를 자동 수정하지 않는다. 자동 생성이 필요한 파일은 생성 원본과 생성 결과를 구분한다.
8. 현재 운영 방식에서는 `CODEOWNERS`를 추가하지 않고 PR에서 필요한 리뷰어를 지정한다.

## 2. 현재 존재하는 하네스 파일

| 경로 | 역할 |
| --- | --- |
| `harness/policy.json` | 필수 경로, 활성 검사와 문서 표식 설정 |
| `harness/src/project_harness/checks.py` | 저장소·JSON·Schema·ID·문서·비밀정보 검사 |
| `harness/src/project_harness/schema.py` | JSON Schema 표준 검증과 로컬 대체 검증 |
| `harness/src/project_harness/cli.py` | 검사 실행과 JSON 리포트 생성 |
| `harness/tests/test_checks.py` | 하네스 검사기의 회귀 테스트 |
| `scripts/run-harness.sh` | 로컬·CI 공통 검사 진입점 |
| `scripts/test-harness.sh` | 하네스와 저장소 테스트 진입점 |
| `.github/workflows/harness.yml` | push·PR 자동 검사 |
| `docs/runbooks/local-harness.md` | 로컬 실행과 실패 대응 |

새 파일은 아래 단계의 시작 조건을 만족할 때만 추가한다.

## 3. 단계별 추가 순서

### 0단계 — 변경 근거 준비

시작 조건:

- 새 기능, 계약, 규칙, 평가 또는 운영 요구가 제안됨

먼저 갱신할 파일:

1. `docs/product/planning-final-v1.1.md`: 제품 범위 변경
2. `docs/product/service-spec.md`: 구현용 요약 변경
3. `docs/decisions/DEC-*.md`: 선택지가 있거나 위험한 변경
4. GitHub Issue: 목적·범위·제외·수용 기준·검증 방법

완료 조건:

- 무엇을 검사해야 하는지와 무엇을 통과로 볼지가 문장으로 설명된다.
- 결정되지 않은 값은 검사 기준으로 사용하지 않는다.

### 1단계 — 실행 단위 품질 검사 연결

시작 조건:

- `apps/api`, `apps/ai_worker` 또는 `apps/training`에 실제 실행 코드와 프로젝트 설정이 추가됨

영역 개발자가 추가하는 파일:

```text
apps/<unit>/pyproject.toml
apps/<unit>/<source directories>
apps/<unit>/<unit tests>
apps/<unit>/Dockerfile        # 컨테이너 사용 시
```

하네스 담당자가 추가하거나 수정하는 파일:

```text
scripts/check-api.sh          # API 포맷·린트·타입·테스트 진입점
scripts/check-ai-worker.sh    # Worker 포맷·린트·타입·테스트 진입점
scripts/check-training.sh     # 학습·평가 코드 검사 진입점
.github/workflows/harness.yml
harness/policy.json
```

진행 순서:

1. 개발자가 재현 가능한 로컬 실행 명령을 제공한다.
2. 하네스 담당자가 해당 명령을 `scripts/check-*.sh` 하나로 묶는다.
3. 로컬에서 실패·성공 사례를 각각 확인한다.
4. 같은 명령을 CI에 연결한다.
5. 실행하지 못한 조건은 리포트와 PR에 남긴다.

완료 조건:

- 로컬과 CI가 같은 명령을 사용한다.
- API, Worker, Training의 의존성 실패가 서로 섞이지 않고 어느 실행 단위에서 실패했는지 표시된다.

### 2단계 — 계약 검증 연결

시작 조건:

- Backend↔AI 또는 Server↔Web 요청·응답·상태·오류 형식이 합의됨

백엔드·AI 개발자가 작성하는 프로젝트 자산:

```text
packages/contracts/README.md
packages/contracts/manifest.json
packages/contracts/schemas/<contract>.schema.json
packages/contracts/examples/<contract>.example.json
packages/contracts/id-registry.json
```

하네스 담당자가 추가하거나 수정하는 파일:

```text
harness/policy.json                         # schema_examples 연결
harness/src/project_harness/checks.py       # 계약 메타·호환성 검사
harness/tests/test_checks.py                # 검사기 실패 회귀 테스트
tests/contracts/test_schema_examples.py     # 실제 계약 예시 검증
tests/contracts/test_compatibility.py       # 제공자·소비자 호환성
```

진행 순서:

1. Schema와 유효·무효 예시를 함께 작성한다.
2. `schema_examples`에 Schema와 예시 경로를 등록한다.
3. 필수 필드, enum, 상태 전이와 오류 형식을 검증한다.
4. 이전 버전 소비자가 깨지는지 확인한다.
5. 서버 구현과 웹 소비 코드를 갱신한다.
6. 계약 버전과 Git SHA를 기록한다.

완료 조건:

- 잘못된 예시가 CI에서 실제로 실패한다.
- 계약 파일이 없거나 경로가 틀린 경우 검사가 건너뛰지 않고 실패한다.
- 웹이 사용하지 않는 내부 AI 필드는 공개 계약에 노출되지 않는다.

### 3단계 — 규칙·프롬프트·기준 데이터 검증

시작 조건:

- 제품 규칙 카탈로그, 실행 프롬프트 또는 선택 가능한 기준 데이터가 실제 자산으로 등록됨

영역 개발자가 작성하는 프로젝트 자산:

```text
rules/<catalog files>
rules/<schema files>
prompts/plan/<prompt files>
prompts/result/<prompt files>
data/reference/manifest.json
data/reference/<approved snapshots>
```

하네스 담당자가 추가하는 검사:

```text
tests/invariants/test_rule_catalog.py
tests/invariants/test_prompt_metadata.py
tests/invariants/test_reference_manifest.py
```

필요한 검증:

- 규칙 ID 중복·누락·상태·판정 방식
- 프롬프트 버전·해시·입력 변수
- 기준 데이터 출처·기준일·체크섬·원문 실재
- `draft` 또는 검토되지 않은 자산의 실행 차단

완료 조건:

- 규칙·프롬프트·기준 데이터 버전을 실행 결과에서 역추적할 수 있다.
- 원문이 없거나 체크섬이 달라지면 CI가 실패한다.

### 4단계 — 제품 AI 안전 불변식 검사

시작 조건:

- 파싱·RMA·검색·판정·CBR·수정안 중 하나 이상의 실제 구현이 추가됨

하네스 담당자가 구현과 함께 작성할 테스트:

```text
tests/invariants/test_source_span_roundtrip.py
tests/invariants/test_evidence_gate.py
tests/invariants/test_withheld_on_validation_failure.py
tests/invariants/test_cbr_preserves_issue.py
tests/integration/test_ai_pipeline_failure_states.py
```

검증할 불변식:

1. 원문 위치가 파싱부터 결과까지 보존된다.
2. 근거가 없으면 확정 판정을 노출하지 않는다.
3. Schema·후검사 실패는 `WITHHELD` 또는 실패 상태로 전달된다.
4. CBR은 확정된 이슈의 개수·위치·등급을 바꾸지 않는다.
5. 선행 단계가 실패하면 후속 단계를 성공으로 표시하지 않는다.

완료 조건:

- 각 불변식에 실패 재현 사례가 한 개 이상 있다.
- 외부 모델이나 법령 API 없이도 핵심 실패를 재현할 수 있다.

### 5단계 — 평가 실행과 회귀 게이트

시작 조건:

- 검수된 평가 인스턴스와 H0 실행 조건이 준비됨

평가 담당자가 작성하는 자산:

```text
evals/datasets/<dataset files>
evals/fixtures/<failure fixtures>
evals/configs/<run config>.json
evals/baselines/<baseline manifest>.json
```

하네스 담당자가 추가하는 파일:

```text
scripts/run-evals.sh
tests/invariants/test_dataset_integrity.py
tests/invariants/test_split_leakage.py
tests/invariants/test_metric_calculation.py
```

자동 생성 파일:

```text
evals/reports/<run-id>/manifest.json
evals/reports/<run-id>/predictions.jsonl
evals/reports/<run-id>/summary.json
```

진행 순서:

1. 데이터셋 Schema와 검수 이력을 확인한다.
2. 같은 `base_doc_id`가 여러 split에 들어가지 않는지 검사한다.
3. 모델·프롬프트·규칙·기준 데이터·Git SHA를 고정한다.
4. H0와 변경안을 동일 조건에서 실행한다.
5. 품질·판단보류율·근거 정확성·시간·비용을 비교한다.
6. 승인된 요약만 Git에 보존하고 실행 산출물은 아티팩트로 관리한다.

완료 조건:

- 동일 설정으로 평가를 재실행할 수 있다.
- 실제로 실행하지 않은 지표는 결과에 포함되지 않는다.
- 통과 기준 완화는 결정 기록과 영향 비교를 요구한다.

### 6단계 — 서버 계약의 웹 자동 동기화

시작 조건:

- `packages/contracts/`가 공식 원천으로 운영되고 계약 버전 방식이 정해짐

추가할 파일:

```text
bareum-server/scripts/export-contracts.sh
bareum-web/scripts/sync-contracts.mjs
bareum-web/contracts/upstream/<version>/...
bareum-web/contracts.lock
bareum-web/.github/workflows/contract-consumer.yml
```

진행 순서:

1. 서버 계약을 버전과 Git SHA가 포함된 산출물로 내보낸다.
2. 웹 동기화 스크립트가 산출물을 받아 `contracts/upstream/`에 저장한다.
3. `contracts.lock`에 적용한 버전과 SHA를 기록한다.
4. 웹의 `src/services/contracts.ts`와 소비자 테스트를 실행한다.
5. 호환되지 않으면 자동 반영하지 않고 PR을 실패시킨다.

완료 조건:

- 서버 계약 변경이 웹 소비자 검사 없이 병합되지 않는다.
- 동기화는 계약 파일만 갱신하며 화면 구현을 자동 수정하지 않는다.
- 같은 계약 버전과 SHA로 재현할 수 있다.

### 7단계 — 운영·보안·리포트 확장

시작 조건:

- 실제 사용자 문서, 외부 API, 모델 키 또는 배포 환경을 사용함

추가할 파일:

```text
harness/report.schema.json
tests/invariants/test_report_schema.py
tests/invariants/test_log_redaction.py
docs/runbooks/ci-harness.md
docs/runbooks/harness-recovery.md
```

필요한 검증:

- 리포트 필드와 상태 호환성
- 로그·Trace·CI 아티팩트의 원문·개인정보·토큰 비노출
- 외부 API 시간 초과·재시도·최종 실패 상태
- 리포트 보존·삭제와 장애 복구 절차

완료 조건:

- 비밀정보가 포함된 실패 사례가 로그에 실제 값으로 남지 않는다.
- CI 장애와 제품 장애를 구분해 복구할 수 있다.

## 4. 파일 추가 시 공통 작업 순서

```text
변경 근거·수용 기준 확인
→ 파일 작성 주체와 검토자 결정
→ 실제 자산 또는 실행 명령 작성
→ 하네스 검사·실패 사례 작성
→ harness/policy.json 연결
→ scripts/ 로컬 진입점 연결
→ GitHub Actions 연결
→ 관련 README·v1.2 문서 갱신
→ 로컬 검사
→ 실패를 고친 뒤 재실행
→ PR에 실행·미수행·호환성·남은 위험 기록
```

필수 확인 명령:

```bash
./scripts/run-harness.sh check
./scripts/test-harness.sh
```

웹 변경이 포함되면 다음도 실행한다.

```bash
cd ../bareum-web
npm run check
```

## 5. 새 하네스 검사 완료 조건

새 검사는 다음 조건을 모두 만족해야 완료로 본다.

- `HAR-*` ID가 있다.
- 검사 대상과 적용 조건이 명확하다.
- 정상 사례가 통과한다.
- 실패 사례가 예상 ID와 메시지로 실패한다.
- 대상 파일이 없을 때 실패·적용 없음 중 어떤 상태인지 정의한다.
- 로컬과 CI가 같은 명령을 실행한다.
- 리포트에 검사 상태와 발견 사항이 남는다.
- 관련 문서와 정책 경로가 실제 파일과 일치한다.
- 제품 코드나 계약 의미를 하네스가 임의로 변경하지 않는다.

## 6. 지금 생성하지 않는 파일

다음 파일은 시작 조건이 충족되기 전에는 빈 형태로 미리 만들지 않는다.

- 실제 API·AI 계약 Schema와 예시
- 실제 규칙 카탈로그와 프롬프트 본문
- 정답셋·H0 기준선·평가 결과
- API·Worker·Training 프로젝트 설정과 Dockerfile
- 서버 계약 내보내기와 웹 동기화 스크립트
- 계약 snapshot과 `contracts.lock`
- 배포·DB·큐·클라우드별 검사

이 파일들은 관련 구현이나 합의가 생긴 시점에 해당 단계의 검사·테스트·문서와 함께 추가한다.
