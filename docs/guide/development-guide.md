# sw-capstone 개발 및 하네스 사용 가이드

이 문서는 `sw-capstone` 프로젝트를 처음 개발하는 사람이 브랜치 생성부터 코드 작성, 검사, 커밋, 푸시, PR, 리뷰와 병합까지 수행하는 전체 과정을 설명한다.

이 프로젝트는 다음 두 GitHub 저장소로 운영한다.

| 작업 내용                               | 저장소                      |
| --------------------------------------- | --------------------------- |
| 화면, 입력, 상태 표시, 공개 API 소비    | `sw-capstone/bareum-web`    |
| 백엔드 API, 작업 관리, AI, 계약, 하네스 | `sw-capstone/bareum-server` |

`bareum-web`은 서버의 공개 API 계약만 소비한다. 서버의 AI 실행 단위, DB, 프롬프트, 규칙 파일을 직접 참조하지 않는다.

## 1. 개발 흐름 한눈에 보기

```text
GitHub 이슈 확인
→ 작업 레포 선택
→ 최신 develop 동기화
→ 이슈 단위 브랜치 생성
→ 기준 문서·계약·AGENTS.md 확인
→ 코드·문서·테스트 작성
→ 로컬 하네스·품질 검사
→ 커밋
→ 원격 푸시
→ Pull Request 생성
→ GitHub Actions·하네스 검사
→ 리뷰 대응
→ 승인
→ Squash Merge
→ 브랜치 삭제·로컬 동기화
```

`main`과 `develop`에는 직접 푸시하지 않는다. 모든 변경은 작업 브랜치와 PR을 통해 반영한다.

## 2. 작업 시작 전 확인할 것

### 2.1 GitHub 이슈

코드 작성 전에 이슈의 다음 항목을 확인한다.

- 변경 목적
- 구현 범위
- 구현하지 않을 범위
- 완료 조건
- 관련 문서·계약·ID
- 결정 대기 사항
- 대상 저장소

예시:

```text
목적: 문서 업로드 화면에서 분석 요청을 시작한다.

범위:
- 파일 선택
- 직접 입력
- 문서 유형 선택
- 검사 범위 선택
- 분석 시작 버튼

제외:
- 실제 문서 파싱
- AI 판정
- 서버 DB 저장

완료 조건:
- 입력값이 없으면 분석을 시작할 수 없다.
- Mock API로 정상·실패 흐름을 확인한다.
- npm run check가 통과한다.
```

기획 문서에 `결정 필요`, `팀 결정`, `멘토사 확인`, `검토`로 표시된 내용은 개발자가 임의로 확정하지 않는다.

### 2.2 기준 문서

프론트 작업은 다음 문서를 먼저 확인한다.

```text
bareum-web/AGENTS.md
bareum-web/README.md
bareum-web/docs/backend-integration.md
```

서버 작업은 다음 문서를 먼저 확인한다.

```text
bareum-server/AGENTS.md
bareum-server/README.md
bareum-server/docs/product/planning-final-v1.1.md
bareum-server/docs/architecture/
bareum-server/docs/decisions/
bareum-server/docs/guide/development-guide.md
bareum-server/docs/architecture/repository-structure.md
```

### 2.3 기존 변경사항

작업 전에 반드시 변경사항을 확인한다.

```bash
git status --short
```

수정된 파일이 이미 있으면 내용을 확인한다. 다른 사람의 작업을 초기화하거나 덮어쓰지 않는다.

## 3. 작업 레포 선택

### 3.1 `bareum-web`에서 작업하는 경우

- 화면과 컴포넌트
- 사용자 입력
- 화면 상태
- 로딩·성공·실패 표시
- 공개 API 호출
- API 응답의 화면 변환
- 프론트 테스트

프론트는 다음 내부 구현에 직접 접근하지 않는다.

```text
bareum-server/apps/ai_worker/
bareum-server/rules/
bareum-server/prompts/
bareum-server/data/
DB
AI 모델 SDK
```

### 3.2 `bareum-server`에서 작업하는 경우

- 공개 API
- 인증·세션
- 파일 업로드
- 분석 작업 생성·조회·재시도
- 영속성
- AI 오케스트레이션
- 계약·규칙·프롬프트·평가·하네스

서버 내부에서도 책임을 분리한다.

| 영역         | 담당 내용                                                 |
| ------------ | --------------------------------------------------------- |
| `apps/api/`  | 세션, 업로드, 작업 상태, 영속성, 공개 API, AI 호출 조정   |
| `apps/ai_worker/` | 파싱, RMA 라우팅, 규칙, 검색, 판정, 수정안, 후검사, Trace |
| `apps/training/` | 모델 학습·평가 실행 단위 |
| `packages/contracts/` | 영역 사이의 요청·응답·상태·오류·데이터 형식               |
| `rules/`     | 결정론 규칙 카탈로그                                      |
| `prompts/`   | 프롬프트와 버전                                           |
| `evals/`     | 평가셋, fixture, 기준선, 결과                             |
| `harness/`   | 저장소·문서·계약·ID·CI 검사                               |

## 4. 브랜치 생성

### 4.1 최신 `develop` 받기

프론트 작업:

```bash
cd bareum-web
git status --short
git switch develop
git pull --ff-only origin develop
```

서버 작업:

```bash
cd bareum-server
git status --short
git switch develop
git pull --ff-only origin develop
```

### 4.2 작업 브랜치 생성

브랜치 이름은 다음 형식을 사용한다.

```text
<type>/#<issue-number>-<short-description>
```

설명 부분은 영문 소문자와 하이픈으로 작성한다.

사용 가능한 유형:

```text
feat, fix, hotfix, release, refactor,
test, docs, chore, ci, perf
```

예시:

```bash
git switch -c feat/#42-document-upload-ui
git switch -c fix/#57-parser-timeout
git switch -c docs/#61-harness-guide
```

작업 브랜치는 GitHub 이슈 하나에 하나만 만든다. 한 브랜치에 서로 관련 없는 여러 기능을 섞지 않는다.

## 5. 코드 작성 순서

### 5.1 단일 레포 기능 변경

화면만 바뀌는 경우:

```text
기획 범위 확인
→ 기존 컴포넌트·Hook 확인
→ 코드 작성
→ 화면 상태별 테스트 작성
→ Mock API로 흐름 확인
→ 프론트 검사
```

서버 내부 기능만 바뀌는 경우:

```text
기획·아키텍처 확인
→ 담당 영역 결정
→ 코드 작성
→ 단위·통합 테스트 작성
→ 서버 하네스 검사
→ 서버 테스트
```

### 5.2 계약 변경

다음과 같은 변경은 계약 변경으로 처리한다.

- API 요청 필드 추가·삭제
- API 응답 필드 추가·삭제
- 상태값 변경
- 오류 코드 변경
- AI 결과 구조 변경
- 필드의 타입·필수 여부 변경
- 프론트와 서버 사이의 데이터 흐름 변경

계약 변경 순서는 다음과 같다.

```text
백엔드·AI 담당자 계약 확정
→ packages/contracts/ Schema·예시 추가
→ 하네스 검사 연결
→ 서버 구현
→ 웹 소비자 영향 확인
→ 통합 테스트
```

계약을 서버와 프론트에서 각각 다르게 해석하지 않는다. 계약이 불명확하면 먼저 결정 기록을 만든다.

### 5.3 프론트 코드의 책임

프론트는 다음 흐름을 사용한다.

```text
화면
→ Hook
→ API 서비스
→ HTTP 클라이언트
→ 백엔드 공개 API
```

화면 컴포넌트에서 직접 `fetch`하지 않는다. 서버의 내부 AI 모듈이나 DB를 직접 호출하지 않는다.

프론트가 확인해야 하는 화면 상태:

- 초기 상태
- 로딩
- 성공
- 부분 실패
- 전체 실패
- 판단보류
- 재시도
- 취소
- 권한 없음

### 5.4 서버·AI 코드의 책임

서버와 AI는 근거와 상태를 보존해야 한다.

- 계약에 맞는 입력·출력 생성
- 작업 상태 전이 검증
- 오류 코드 보존
- 원문 위치와 근거 보존
- 검증되지 않은 결과의 판단보류 처리
- 규칙·프롬프트·데이터·모델 버전 기록
- Trace와 Git SHA 기록

근거가 없거나 Schema 후검사를 통과하지 못한 결과를 확정 위반으로 노출하지 않는다.

## 6. 개발 중 확인

작업 중에는 반복해서 다음 명령을 실행한다.

```bash
git status --short
git diff
```

확인할 내용:

- 의도하지 않은 파일이 변경되지 않았는가?
- `.env`, 비밀번호, 토큰이 포함되지 않았는가?
- 빌드 결과물이 추가되지 않았는가?
- 다른 사람의 변경이 섞이지 않았는가?
- 계약 변경 시 예시와 테스트도 변경했는가?
- 문서 링크가 실제 파일을 가리키는가?
- 결정되지 않은 사항을 임의로 확정하지 않았는가?

## 7. 로컬 검사

### 7.1 서버 검사

```bash
cd bareum-server
./scripts/run-harness.sh check
./scripts/test-harness.sh
```

`run-harness.sh check`는 다음을 검사한다.

- 필수 경로
- JSON 형식
- Schema 헤더
- ID 등록·중복·참조
- Markdown 링크
- 비밀정보 패턴
- 기획 문서 기준
- 백엔드 문서 상태

`test-harness.sh`는 하네스 자체 테스트와 `tests/` 아래에 등록된 계약·통합·불변식 테스트를 실행한다.

### 7.2 웹 검사

```bash
cd bareum-web
npm install
npm run check
```

현재 공개 계약이 확정되지 않아 계약 스냅샷 검사를 실행하지 않는다. 계약이 추가되면 별도 검사 명령과 소비자 검증을 같은 PR에 연결한다.

`npm run check`는 다음을 실행한다.

```text
Prettier
ESLint
Vitest
TypeScript
Vite production build
```

Mock API 성공은 실제 서버 연동 성공을 의미하지 않는다. 실제 연동에서는 요청 형식, 분석 ID, 상태 폴링, 결과 Schema, 오류 코드, 취소, 파일 다운로드를 별도로 확인한다.

## 8. 하네스 검사 결과 해석

| 결과     | 의미                                     | 개발자가 할 일                 |
| -------- | ---------------------------------------- | ------------------------------ |
| `PASSED` | 활성화된 검사를 통과함                   | PR에 실행 결과 기록            |
| `ERROR`  | 병합 전 해결해야 하는 실패               | 파일·ID·원인을 수정하고 재실행 |
| 적용 없음 | 검사에 필요한 대상 파일이나 설정이 없음  | 대상이 추가될 때 검사를 연결   |
| 비활성   | 정책에서 검사를 실행하지 않도록 설정함    | 비활성 사유와 영향을 검토      |
| 미연결   | 검사 대상 구현이나 실행 경로가 아직 없음 | 성공으로 기록하지 않음         |
| 판단보류 | 근거가 부족해 확정할 수 없음             | 사람 검토 또는 근거 확보       |

주요 서버 하네스 ID:

```text
HAR-STRUCT-001   필수 경로
HAR-POLICY-001   하네스 정책
HAR-JSON-001     JSON
HAR-SCHEMA-001  Schema
HAR-ID-*         ID
HAR-DOC-*        문서 링크
HAR-SEC-001      비밀정보
HAR-PRODUCT-001  기획 문서
HAR-BACKEND-001  백엔드 문서
```

검사 실패를 숨기거나 검사 파일을 제외하지 않는다. 리포트 파일을 삭제해서 통과시키지 않는다.

## 9. 커밋

검사 전에 변경사항을 확인한다.

```bash
git status --short
git diff
```

필요한 파일만 스테이징한다.

```bash
git add src/features/upload/UploadPage.tsx
git add src/hooks/useDocumentUpload.ts
git add src/hooks/useDocumentUpload.test.ts
git diff --staged
```

커밋 형식:

```text
<type>: <한글 변경 내용>
```

예시:

```bash
git commit -m "feat: 문서 업로드 입력 검증 추가"
git commit -m "fix: 분석 실패 상태 표시 수정"
git commit -m "test: 분석 상태 계약 테스트 추가"
git commit -m "docs: 하네스 사용 가이드 보완"
```

한 커밋에는 하나의 논리적 변경만 담는다. `수정`, `완료`, `update`처럼 목적이 드러나지 않는 메시지는 사용하지 않는다.

## 10. 푸시

처음 푸시할 때:

```bash
git push -u origin HEAD
```

그 이후에는:

```bash
git push
```

공유한 브랜치를 강제로 덮어쓰지 않는다.

```bash
# 사용하지 않음
git push --force
```

## 11. PR 생성

일반 변경은 `develop`, 릴리스·완료 변경은 `main`을 대상으로 한다.

PR 제목:

```text
[Type] 변경 내용
```

예시:

```text
[Feat] 문서 업로드 화면 추가
[Fix] 분석 실패 상태 표시 수정
[Docs] 하네스 사용 가이드 보완
[Test] 분석 상태 계약 테스트 추가
```

PR 본문에는 다음을 적는다.

```text
## 변경 목적

## 변경 범위

## 관련 이슈
Closes #42

## 계약 영향
- 계약 변경 없음
또는
- 변경한 계약 파일
- 웹 소비자 영향

## 실행한 검사
- ./scripts/run-harness.sh check
- ./scripts/test-harness.sh
- npm run check

## 미실행 검사

## 개인정보·권한 영향

## 남은 위험
```

현재 운영 방식에서는 `CODEOWNERS`를 사용하지 않는다. PR 작성자가 필요한 리뷰어를 직접 지정한다.

## 12. GitHub Actions와 리뷰

PR을 올리면 GitHub Actions가 실행된다.

서버:

```text
Harness / check
├── ./scripts/run-harness.sh check
└── ./scripts/test-harness.sh
```

웹:

```text
Harness / check
└── npm run check
```

### 12.1 현재 자동으로 확인하는 범위

서버 하네스와 CI는 현재 다음을 확인한다.

- 저장소 구조
- 필수 문서와 링크
- JSON 문법과 존재하는 Schema의 필수 헤더
- 정책에 등록된 Schema·예시 쌍
- ID 레지스트리가 있을 때 ID 형식·중복·참조
- 비밀정보 패턴
- 기획 기준본의 필수 표식
- 백엔드 책임 문서의 검증 항목
- 하네스 자체 회귀 테스트

웹 CI는 `npm run check`를 통해 포맷, ESLint, 단위 테스트, TypeScript 검사와 프로덕션 빌드를 실행한다.

계약 Schema·예시 쌍과 ID 레지스트리처럼 현재 하네스에 등록됐지만 대상이 아직 없는 검사는 통과가 아니라 `not_applicable`로 기록한다. 평가 데이터 검사는 평가 자산과 기준이 준비된 뒤 하네스에 새로 연결한다. 백엔드·AI 실행 코드의 포맷·린트·타입·기능 테스트도 실제 코드와 실행 명령이 추가된 뒤 연결한다.

하네스는 다음을 하지 않는다.

- 프론트·백엔드 코드를 대신 작성하지 않음
- 실패한 코드를 자동 수정하지 않음
- 리뷰어를 자동 지정하지 않음
- API 설계 결정을 대신 내리지 않음
- UI 사용성을 판단하지 않음
- AI 결과가 실제로 정확한지 자동 확정하지 않음
- 결정 대기 사항을 임의로 확정하지 않음

### 12.2 향후 확장되는 범위

프로젝트 구현이 진행되면 하네스는 다음 순서로 확장된다.

| 추가되는 대상 | 하네스에 추가되는 검사 | 팀원이 해야 하는 일 |
| --- | --- | --- |
| API·AI Worker·Training 코드 | 영역별 포맷·린트·타입·단위·통합·빌드 검사 | 실행 가능한 코드와 테스트 명령 제공 |
| 요청·응답·상태·오류 계약 | Schema·예시 검증, 이전 버전 호환성 검사 | 백엔드·AI가 계약을 작성하고 프론트가 소비 영향 확인 |
| 규칙·프롬프트·기준 데이터 | ID·버전·해시·출처·기준일·체크섬 검사 | 변경 이유와 사용한 자산 버전 기록 |
| 파싱·검색·판정·수정안 구현 | 원문 위치, 근거 게이트, 실패 상태와 CBR 안전 불변식 검사 | 정상·실패 사례와 기대 결과 제공 |
| 검수된 평가셋과 H0 | 데이터 누수, 지표 계산과 성능 회귀 검사 | 동일 조건의 평가 설정과 검수 이력 제공 |
| 서버 계약의 웹 동기화 | 계약 버전·Git SHA·웹 소비자 호환성 검사 | 프론트 소비 코드는 사람이 수정하고 검사 결과 확인 |
| 실제 배포·외부 API·사용자 문서 | 리포트 Schema, 로그 마스킹, 재시도·복구 검사 | 보존·삭제·권한·장애 대응 기준 제공 |

확장되더라도 하네스가 기능 코드를 대신 작성하거나 자동 수정하는 것은 아니다. 개발자는 실제 코드·계약·테스트와 실패 사례를 작성하고, 하네스 담당자는 이를 반복 실행할 수 있는 검사·리포트·CI로 연결한다.

단계별 시작 조건, 추가할 파일과 완료 조건은 [`harness-file-roadmap.md`](../harness/harness-file-roadmap.md)를 따른다.

## 13. CI 실패 대응

CI가 실패하면 같은 작업 브랜치에서 수정한다.

```bash
git status --short
git diff
```

서버:

```bash
./scripts/run-harness.sh check
./scripts/test-harness.sh
```

웹:

```bash
npm run check
```

수정 후 새 커밋을 만들고 푸시한다.

```bash
git add <수정한-파일>
git commit -m "fix: 하네스 실패 원인 수정"
git push
```

하네스가 실패한 상태에서 PR을 병합하지 않는다.

## 14. 기준 브랜치 변경 반영

PR이 오래 열려 `develop`에 새 변경이 들어왔거나 충돌이 발생하면 작업 브랜치에서 기준 브랜치를 반영한다.

```bash
git fetch origin
git merge origin/develop
```

충돌 해결 후 검사한다.

```bash
# 서버
./scripts/run-harness.sh check
./scripts/test-harness.sh

# 웹
npm run check
```

공유 브랜치는 리베이스하거나 강제 푸시하지 않는다. 기준 브랜치를 `merge`하는 방식을 사용한다.

## 15. 승인과 병합

병합 전 조건:

- PR 목적과 범위가 명확함
- 관련 이슈 연결
- 필수 검사 통과
- 최소 1명 승인
- 해결되지 않은 리뷰 대화 없음
- 계약·하네스·평가 변경 시 관련 담당자 검토
- 최신 기준 브랜치 반영

병합은 `Squash Merge`만 사용한다.

병합 후 로컬을 정리한다.

```bash
git switch develop
git pull --ff-only origin develop
git branch -d feat/#42-document-upload-ui
```

## 16. 계약 변경의 전체 예시

### 16.1 서버 계약 PR

```bash
cd bareum-server
git switch develop
git pull --ff-only origin develop
git switch -c feat/#80-analysis-status-contract
```

작업 순서:

```text
packages/contracts/ Schema 수정
→ 계약 예시 수정
→ 계약 테스트 수정
→ 서버 구현
→ 서버 하네스·테스트 실행
→ 서버 PR 생성
```

```bash
./scripts/run-harness.sh check
./scripts/test-harness.sh
git add apps/ packages/contracts/ tests/ docs/architecture/
git commit -m "feat: 분석 상태 계약 추가"
git push -u origin HEAD
```

### 16.2 웹 소비자 PR

서버 계약 변경을 확인한 뒤 웹에서 작업한다.

```bash
cd bareum-web
git switch develop
git pull --ff-only origin develop
git switch -c feat/#80-analysis-status-contract-web
```

확인할 것:

- 기존 프론트 Schema 영향
- 상태별 화면 표시
- Mock 응답과 테스트

```bash
npm run check
git add src/ docs/
git commit -m "feat: 분석 상태 계약 소비 연결"
git push -u origin HEAD
```

서버 PR과 웹 PR은 서로 연결한다. 서버 계약이 확정되지 않았는데 웹이 임의의 계약을 먼저 확정하지 않는다.

## 17. 사람과 하네스의 역할

| 단계      | 사람이 하는 일           | 하네스가 하는 일          |
| --------- | ------------------------ | ------------------------- |
| 요구사항  | 기획 범위·완료 조건 해석 | 기준 문서 존재 확인       |
| 설계      | API·화면·AI 구조 결정    | 계약·문서 연결 확인       |
| 구현      | 실제 코드 작성           | 코드를 대신 작성하지 않음 |
| 테스트    | 기능 테스트 작성         | 검사·회귀 테스트 실행     |
| 실패 대응 | 원인 수정                | 실패 ID·파일·메시지 제공  |
| 리뷰      | 설계·품질·사용성 판단    | 자동 검사 결과 제공       |
| PR        | 변경 설명·리뷰어 지정    | CI 검사 실행              |
| 병합      | 승인·병합 결정           | 필수 상태 검사 결과 제공  |

최종적으로 개발자는 다음 순서를 기억하면 된다.

```text
브랜치 생성
→ 코드 작성
→ 로컬 검사
→ 커밋
→ 푸시
→ PR
→ 하네스·CI 확인
→ 실패 수정
→ 리뷰 승인
→ Squash Merge
```

하네스는 개발자를 대신해 개발하는 도구가 아니라, 사람이 만든 변경이 프로젝트의 계약·구조·검증 기준을 지키는지 확인하는 게이트다.
