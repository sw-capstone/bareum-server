# bareum-server — 백엔드 서비스 저장소

`sw-capstone/bareum-server`는 바름 서비스의 백엔드 API와 서버 운영 경계를 관리하는 저장소다. 세션·분석 작업·영속성·공개 API를 담당하며, AI 처리는 별도 `bareum-server-ai` 저장소에서 관리한다. 프론트엔드는 `bareum-web`에서 공개 계약을 소비한다.

## 담당 범위

- `packages/contracts/`: 형식이 확정되면 추가할 서버·프론트엔드 공유 계약 경로
- 서버↔AI 계약도 관련 담당자가 합의한 뒤 같은 계약 관리 경로에 추가하고, 서버·AI의 제공자·소비자 테스트로 검증한다.
- `src/main/`, `src/test/`: Spring Boot 애플리케이션 코드와 테스트
- `harness/`: 서버 저장소와 문서 정적 검사를 실행하는 서버 하네스

백엔드와 AI는 저장소를 분리해 관리한다. 영역 간 형식은 합의된 계약으로 연결하며, AI 결과의 근거와 안전 조건은 `bareum-server-ai`의 구현·검사 기준과 함께 검토한다.

## 서버 실행 환경

GitHub develop의 초기 설정은 Java 25, Spring Boot 4.1.1, Gradle Wrapper 9.7.1을 사용한다. Java 25 설치 후 `./gradlew test`로 테스트하고 `./gradlew bootRun`으로 실행한다. Pull Request에서는 서버 CI가 `./gradlew test`와 하네스 검사를 각각 실행한다.

## 저장소 구조

```text
.github/        이슈·PR 템플릿과 서버 테스트·하네스 CI
docs/           기획·아키텍처·결정·개발·하네스 문서
harness/        하네스 정책·검사기·자체 테스트
scripts/        로컬과 CI의 공통 실행 진입점
```

`packages/contracts/`는 서버와 다른 저장소가 공유할 계약이 확정되면 추가할 위치이며, 현재 구조에는 포함되지 않는다. 백엔드 실행 코드의 경로와 AI 저장소의 구현·평가 자산 경로는 각각 관련 결정 후 정한다.

각 디렉터리의 세부 책임과 경계는 [`docs/architecture/repository-structure.md`](docs/architecture/repository-structure.md)와 [`docs/architecture/repository-boundaries.md`](docs/architecture/repository-boundaries.md)를 기준으로 한다. 모델, 프레임워크, 세부 수치와 팀 역할은 확정된 기획문서와 팀 결정에 따라 반영한다.

## 문서 안내

- 저장소 구조: [`docs/architecture/repository-structure.md`](docs/architecture/repository-structure.md)
- 아키텍처 경계: [`docs/architecture/repository-boundaries.md`](docs/architecture/repository-boundaries.md)
- 개발 절차: [`docs/guide/development-guide.md`](docs/guide/development-guide.md)
- 제품 기획·서비스 명세: [`docs/product/README.md`](docs/product/README.md)

### 하네스 검증

하네스는 백엔드·AI 구현을 대신하는 기능이 아니다. 현재 서버 하네스는 필수 경로·JSON·문서 링크·비밀정보 패턴·서버 구현 요약 문서의 필수 표식을 검사한다. Schema·ID 검사는 코드가 있지만 대상이 없어 `not_applicable`이며, 계약 호환성·AI 근거·안전성·성능 회귀는 관련 구현과 자산이 준비된 뒤 각 저장소의 검사에 연결한다. 적용 범위와 확장 기준은 [`docs/guide/development-guide.md`](docs/guide/development-guide.md), 구조 설계는 [`docs/harness/harness-v1.4.md`](docs/harness/harness-v1.4.md)에서 확인한다.

```bash
./scripts/run-harness.sh check
./scripts/test-harness.sh
```
