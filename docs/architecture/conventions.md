# 개발 컨벤션

이 문서는 백엔드·AI와 서버 레포에 적용하는 작업 규칙을 요약한다.

## Git Workflow

- Git Flow를 사용한다: `main`, `develop`.
- 모든 PR은 Squash Merge한다.
- `main`과 `develop`에는 직접 푸시하지 않는다.

## 브랜치

```text
<type>/#<issue-number>-<short-description>
```

설명은 영문 소문자와 하이픈을 사용하고, 한 브랜치에는 하나의 작업 목적만 담는다.

예시:

```text
feat/#12-summary-api
fix/#23-empty-model-response
docs/#8-git-convention
```

| Type | 용도 |
| --- | --- |
| `feat` | 기능 추가 |
| `fix` | 버그 수정 |
| `hotfix` | 운영 긴급 수정 |
| `release` | 배포 준비 |
| `refactor` | 동작을 유지하는 구조 개선 |
| `test` | 테스트 추가·수정 |
| `docs` | 문서 변경 |
| `chore` | 의존성·설정 변경 |
| `ci` | CI/CD 변경 |
| `perf` | 성능 개선 |

## 커밋

```text
<type>: <한글 변경 내용>
```

- Type은 영문 소문자를 사용하고 설명은 한글로 작성한다.
- 제목 끝에 마침표를 붙이지 않는다.
- 하나의 커밋에는 하나의 논리적 변경만 담는다.
- `수정`, `완료`, `update`처럼 모호한 제목은 사용하지 않는다.
- 긴급 수정은 `fix`, 배포 준비는 `chore` Type을 사용한다.

예시:

```text
feat: 문서 요약 API 추가
fix: 모델의 빈 응답 처리
refactor: 프롬프트 로딩 코드 분리
test: 모델 호출 실패 테스트 추가
docs: 실행 방법 작성
```

## PR

제목은 다음 형식을 사용한다.

```text
[Type] 변경 내용
```

사용 태그는 `[Feat]`, `[Fix]`, `[Hotfix]`, `[Release]`, `[Refactor]`, `[Test]`, `[Docs]`, `[Chore]`, `[CI]`, `[Perf]`다.

PR은 하나의 목적에 집중하고 관련 이슈를 연결하며 변경 목적과 검증 결과를 작성한다.

## 리뷰 우선순위

리뷰 코멘트 앞에 `P1:`부터 `P5:`까지 우선순위를 붙인다.

| 우선순위 | 의미 |
| --- | --- |
| `P1` | 반드시 반영 |
| `P2` | 적극적으로 검토하여 반영 여부 결정 |
| `P3` | 가능하면 반영 권장 |
| `P4` | 반영 여부 자유 |
| `P5` | 사소한 의견 |

## 기준 문서

- 브랜치·PR·병합과 GitHub 보호 설정: [`DEC-GIT-001`](../decisions/DEC-GIT-001-github-flow.md)
- 작업 순서와 명령: [`development-guide.md`](../guide/development-guide.md)
- 하네스 검사: [`harness-v1.2.md`](../harness/harness-v1.2.md)
