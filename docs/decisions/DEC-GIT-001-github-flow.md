# DEC-GIT-001 GitHub 운영 규칙

- 상태: 결정
- 결정일: 2026-09-08
- 소유 역할: `harness-maintainers`
- 적용 범위: 프로젝트에서 사용하는 모든 GitHub 저장소

## 결정

### 브랜치

- Git Flow의 기준 브랜치는 `main`과 `develop`이다.
- `main`과 `develop`은 직접 푸시하지 않는다.
- 작업은 GitHub 이슈 하나당 하나의 짧은 브랜치에서 진행한다.
- 브랜치 이름은 `<type>/#<issue-number>-<short-description>` 형식을 사용한다.
- 설명은 영문 소문자와 하이픈을 사용한다.
- `type`은 `feat`, `fix`, `hotfix`, `release`, `refactor`, `test`, `docs`, `chore`, `ci`, `perf` 중에서 선택한다.
- 예시: `feat/#42-document-upload`, `fix/#57-parser-timeout`
- 원격에 공유한 작업 브랜치는 리베이스하지 않고 기준 브랜치의 최신 변경을 `git merge origin/<base>`로 반영한다.

### 커밋

- `<type>: <한글 변경 내용>` 형식을 사용한다.
- Type은 영문 소문자를 사용하고 설명은 한글로 작성한다.
- 제목 끝에 마침표를 붙이지 않는다.
- 한 커밋에는 하나의 논리적 변경만 담는다.
- `수정`, `완료`, `update`처럼 모호한 제목은 사용하지 않는다.
- 예시: `feat: 문서 파싱 결과 계약 추가`

### PR과 리뷰

- PR 제목은 `[Type] 변경 내용` 형식을 사용한다.
- 사용 태그는 `[Feat]`, `[Fix]`, `[Hotfix]`, `[Release]`, `[Refactor]`, `[Test]`, `[Docs]`, `[Chore]`, `[CI]`, `[Perf]`다.
- 모든 변경은 PR로 `develop`에 병합하고, 릴리스·완료 변경만 `main`에 병합한다.
- 작업 중 공유가 필요하면 Draft PR을 먼저 열 수 있다.
- 하나의 PR에는 하나의 목적만 담고 관련 이슈·변경 목적·검증 결과를 작성한다.
- 최소 1명의 승인을 요구한다.
- 새 커밋으로 내용이 바뀌면 이전 승인은 해제한다.
- 해결되지 않은 리뷰 대화가 있으면 병합하지 않는다.
- 리뷰 코멘트에는 `P1:`~`P5:` 우선순위를 붙인다.
- 계약·하네스·평가·기준 데이터 변경은 관련 담당자의 검토를 추가로 요구한다.

### GitHub 권한 소유

- 저장소 관리자 설정, Branch protection·Ruleset, Actions 설정·시크릿과 병합 권한은 하네스 주 담당자에게 통합한다.
- 하네스 주 담당자는 `harness-maintainers` 역할로 기록하고, 실제 GitHub 계정은 역할 확정 후 연결한다.
- 영역 담당자는 자신의 영역 PR을 검토할 수 있지만 저장소 보안 설정과 필수 검사 정책은 변경하지 않는다.
- 주 담당자 부재에 대비한 보조 담당자는 최소 유지보수 권한만 부여하며, 권한 부여·회수 이력은 결정 기록에 남긴다.

### CI와 병합

- `Harness / check`를 필수 상태 검사로 지정한다.
- `develop` 대상 PR은 최신 `develop`, `main` 대상 PR은 최신 `main`을 반영한 상태에서 필수 검사를 다시 통과해야 한다.
- 병합 방식은 Squash Merge만 허용한다.
- 병합 후 원격 작업 브랜치는 자동 삭제한다.
- `main`의 강제 푸시와 삭제를 금지한다.
- 공유한 작업 브랜치도 강제 푸시하지 않는다.
- 관리자 우회는 장애 복구처럼 불가피한 경우에만 사용하고 사유를 결정 기록이나 장애 기록에 남긴다.

## GitHub 설정값

저장소가 확정되면 `main`과 `develop` Branch protection 또는 Ruleset에 다음 값을 적용한다.

| 설정 | 값 |
|---|---|
| Require a pull request before merging | 사용 |
| Required approvals | 1 |
| Dismiss stale pull request approvals | 사용 |
| Require conversation resolution | 사용 |
| Require status checks | `Harness / check` |
| Require branches to be up to date | 사용 |
| Require linear history | 사용 |
| Allow force pushes | 사용 안 함 |
| Allow deletions | 사용 안 함 |
| Merge methods | Squash Merge만 사용 |
| Automatically delete head branches | 사용 |

## 이유

Git Flow로 개발 통합 기준(`develop`)과 릴리스 기준(`main`)을 분리하고, Squash Merge로 목적 단위 변경을 남긴다. 승인 1명은 병목을 줄이면서 최소한의 교차 검토를 보장한다.

## 재검토 조건

- 리뷰 대기 시간이 반복적으로 개발을 막는 경우
- 저장소 분리 후 저장소별 배포·권한 정책이 달라지는 경우
- 하네스 주 담당자에게 권한을 통합하는 방식이 병목 또는 단일 장애점이 되는 경우
- 릴리스 브랜치나 긴급 패치 절차가 필요한 운영 단계에 들어가는 경우
