# 저장소 구조와 실행 경계

## 현재 저장소 결정

제품 코드는 멀티레포로 운영한다.

| 저장소 | 책임 |
| --- | --- |
| `sw-capstone/bareum-web` | 화면과 공개 API 계약 소비 |
| `sw-capstone/bareum-server` | 백엔드 API, 서버 문서, 공유 계약과 서버 하네스 |
| `sw-capstone/bareum-server-ai` | AI 처리 코드와 관련 테스트·평가 |

백엔드와 AI는 별도 Git 저장소에서 관리한다. 저장소를 분리한다고 해서 실행·배포 방식이나 내부 아키텍처까지 확정된 것은 아니다.

## 현재 저장소 구조

아래는 관리 대상 디렉터리다. `.git`, 설치 의존성, 빌드 결과와 실행 캐시는 생략했다.

```text
bareum-web/
├── .github/                 # 협업 템플릿과 웹 CI
├── docs/                    # 백엔드 연동 안내
├── public/fonts/            # 정적 글꼴
└── src/
    ├── app/                # 라우팅·Provider
    ├── components/ui/      # 공통 UI
    ├── config/             # 환경 설정
    ├── contexts/           # 세션 상태
    ├── features/           # 화면별 기능
    ├── hooks/              # 화면 동작
    ├── mocks/              # 샘플 응답
    ├── services/           # API 호출·응답 검증
    ├── styles/             # 공통 스타일
    └── test/               # 테스트 환경

bareum-server/
├── .github/                 # 협업 템플릿과 서버 테스트·하네스 CI
├── src/main/                # Spring Boot 초기 코드·설정
├── src/test/                # Spring Boot 테스트
├── gradle/wrapper/          # Gradle Wrapper
├── docs/
│   ├── architecture/        # 저장소 구조·책임 경계
│   ├── decisions/           # 결정과 검토 제안
│   ├── guide/               # 공통 개발 안내
│   ├── harness/             # 하네스 설계·로드맵
│   └── product/             # 서버 구현 요약
├── harness/
│   ├── src/project_harness/ # 검사기·CLI·Schema 검증
│   ├── tests/               # 검사기 자체 테스트
│   └── reports/             # 생성 결과(Git 제외)
└── scripts/                 # 서버 검사 실행 명령

bareum-server-ai/
├── README.md                # AI 저장소 범위와 현재 상태
├── AGENTS.md                # AI 저장소 작업 지침
├── .github/                 # 협업 템플릿·하네스 CI
├── docs/                    # 설명·설계·가이드
├── harness/                 # 저장소 정적 검사·자체 테스트
├── scripts/                 # 검사 명령
└── .gitignore               # 제외 규칙
```

`bareum-server-ai`는 AI 저장소의 문서·협업 템플릿·하네스 CI를 관리한다. AI 실행 코드의 경로와 실행·배포 단위는 관련 결정이 완료된 뒤 구현과 함께 추가한다.

웹·서버 루트의 README, AGENTS와 설정 파일도 각 저장소에서 관리한다. 발표용 HTML·다이어그램 원본은 제품 레포 밖에서 관리하며, 서버 검사의 대상으로 포함하지 않는다.

## 현재 구조의 적용 범위

이 문서는 확정된 저장소 분리와 현재 관리 대상 디렉터리만 정의한다. 백엔드·AI 실행 코드, 공유 계약, 인프라·배포 설정은 각 결정이 완료된 뒤 해당 구현과 함께 추가한다. 서버 하네스는 결정되지 않은 경로를 검사 기준으로 사용하지 않는다.

## 관련 문서

- [`repository-boundaries.md`](repository-boundaries.md)
- [`DEC-REPO-001`](../decisions/DEC-REPO-001-repository-strategy.md)
