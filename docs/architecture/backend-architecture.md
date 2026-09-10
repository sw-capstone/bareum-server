# 백엔드 아키텍처

백엔드는 기능별 모듈과 레이어드 구조를 기본으로 사용하고, AI 연동은 Port·Adapter 경계 뒤에 둔다. 백엔드 전체를 엄격한 헥사고날 아키텍처로 간주하지 않는다.

## 책임 흐름

```text
FastAPI Router → 기능 Service → Repository → DB
                         └→ AnalysisEngine Port
                                └→ 선택한 Adapter → AI
```

- Router: 요청 검증과 HTTP 응답. 업무 로직·직접 SQL·직접 AI 호출은 두지 않는다.
- Service: 권한·소유권과 업무 순서. DB는 Repository, AI는 Port를 사용한다.
- Repository: 조회·저장과 유스케이스 단위의 트랜잭션 경계를 담당한다.
- Port: 백엔드가 AI에 요구하는 입출력 인터페이스를 정의한다. HTTPX나 특정 AI SDK에 의존하지 않는다.
- Adapter: Port 구현체이며 외부 응답·오류를 계약으로 변환하고 타임아웃을 처리한다.
- Dependencies: 선택한 Adapter를 조립해 Service에 주입한다.

## 기능 모듈 예시

실제 구현 시 기능별로 `users`, `documents`, `analyses`를 묶고 각 기능에서 필요한 `router`, `schemas`, `service`, `repository`, `models`를 관리한다. `analyses`에는 AI 연동을 위한 `ports`, `dependencies`, `adapters/fake.py`를 둘 수 있다.

실행 코드는 `apps/api/`에 두고, 이 문서는 API·작업·저장소·AI 연동의 책임 경계를 설명한다.

| 현재 문서 경로 | 구현 책임 |
| --- | --- |
| `apps/api/` | 공개 요청·응답·오류와 HTTP 경계 |
| `apps/api/` | 작업 생성·멱등성·상태 오케스트레이션 |
| `apps/api/` | 원문·결과·Trace 저장·조회·삭제 |
| `packages/contracts/` | 백엔드·AI·프론트 사이의 형식 |

## 현재 결정과 보류

- 초기에는 작업 큐를 도입하지 않는다. 처리시간·동시성·재시도·복구 요구가 확인되면 재검토한다.
- AI가 같은 Python 프로세스의 패키지인지 별도 HTTP 서비스인지, 또는 Worker 컨테이너인지 아직 확정하지 않는다.
- 레포는 하나로 유지하되, 실행 단위는 API와 AI Worker로 분리할 수 있도록 Port·Adapter와 계약 경계를 먼저 만든다.
- 큐 도입으로 접수 후 조회 방식이 필요해지면 API와 상태 계약도 함께 변경한다. Adapter 교체만으로 해결되는 변경으로 간주하지 않는다.

## 하네스 검증 연결

변경 순서는 계약 확인 또는 변경 → Service·Port·Adapter 구현 → Fake Adapter 흐름 검증 → 실제 통신·저장 검증이다. API 계층에서 모델 SDK나 AI 내부 프롬프트를 직접 호출하면 경계 위반으로 본다.

실행 명령이 연결되기 전까지 포맷·린트·타입·단위·API·DB/마이그레이션 검사는 `미연결` 또는 `미수행`으로 기록한다. 문서 검사 통과를 백엔드 기능 구현 완료로 표시하지 않는다.

원문 삭제, 개인정보 로그 비노출, 상태 전이, 부분 실패, 판단보류, Trace 연결은 구현 방식과 무관하게 보존해야 하는 계약 경계다.

## 출처

이 문서는 공유된 `백엔드 아키텍처` 문서의 제안과 서버 레포의 책임 문서를 구현 기준으로 정리한 것이다. 기술 스택·큐·통신 방식은 구현 환경과 운영 요구에 맞춰 결정 기록으로 관리한다.
