# 로컬 하네스 실행

## 요구 환경

- Python 3.10 이상
- 저장소 루트에서 명령 실행
- 기본 검사에는 외부 패키지가 필요하지 않음

## 실행

```bash
./scripts/run-harness.sh check
./scripts/test-harness.sh
```

첫 명령은 구조·JSON·Schema 헤더·ID·문서 링크·비밀정보 패턴을 검사하고 `harness/reports/report.json`을 만든다. 리포트는 각 검사를 `passed`, `failed`, `not_applicable`, `disabled`로 구분한다. 두 번째 명령은 하네스 회귀 테스트와 `tests/` 아래에 등록된 저장소 테스트를 실행한다.

Schema 예시 검사는 `harness/requirements.txt`의 `jsonschema`가 설치되어 있으면 Draft 2020-12 표준 검증기를 사용한다. 의존성이 없는 로컬 환경에서는 동일한 프로젝트 계약에 필요한 내장 검증기로 동작하지만, CI는 항상 `jsonschema`를 설치한다.

## 실패 대응

1. 콘솔 또는 JSON 리포트에서 검사 ID와 파일을 확인한다.
2. 제품 의미나 계약을 변경해야 한다면 관련 기획과 결정 기록을 먼저 확인한다.
3. 같은 명령을 다시 실행한다.
4. 검사 자체가 잘못됐다면 재현 테스트와 함께 하네스를 수정한다.
