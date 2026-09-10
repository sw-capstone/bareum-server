<!-- PR 제목은 [Feat] 변경 내용 형식을 사용한다. 공식 ID가 있으면 본문에 기록한다. -->

## PR 제목과 대상 브랜치

- 제목 태그: `[Feat]` / `[Fix]` / `[Hotfix]` / `[Release]` / `[Refactor]` / `[Test]` / `[Docs]` / `[Chore]` / `[CI]` / `[Perf]`
- 대상 브랜치: 일반 변경은 `develop`, 릴리스·완료 변경은 `main`

## 변경 목적과 관련 이슈

- Closes #
- 관련 프로젝트 ID:

## 변경한 영역·계약·ID

- 기획 기준본 확인: `docs/product/planning-final-v1.1.md`
- 구현 요약 확인: `docs/product/service-spec.md`
- 백엔드 변경 시 세션·작업·상태·API·영속성 경계 확인:

## 실행한 검증

- [ ] `./scripts/run-harness.sh check`
- [ ] `./scripts/test-harness.sh`
- [ ] 백엔드 영역별 검사(포맷·린트·타입·단위·API·DB/마이그레이션)를 실행했거나 미연결·미수행 사유를 적었다.

## 병합 체크리스트

- [ ] 하나의 이슈와 목적에 집중한 변경이다.
- [ ] 필요한 ID를 `packages/contracts/id-registry.json`에 등록했다.
- [ ] 대상 기준 브랜치(`develop` 또는 `main`)의 최신 변경을 반영했다.
- [ ] 관련 리뷰 대화를 모두 해결했다.
- [ ] 리뷰 코멘트에 필요한 경우 `P1:`~`P5:` 우선순위를 표시했다.

## 실행하지 못한 검사와 이유

## 호환성·개인정보·권한 영향

## 남은 위험과 재검토 조건
