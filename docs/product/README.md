# 기획 문서 관리

## GitHub 기준 문서

[`planning-final-v1.1.md`](planning-final-v1.1.md)는 협업 문서의 기획 문서 최종본을 GitHub에서 관리하는 별도 기준 문서다.

- 문서 버전: `v1.1`
- 관리 경로: `docs/product/planning-final-v1.1.md`
- 변경 순서: 기획 변경 제안 → 이슈와 결정 상태 확인 → 기준 문서 수정 → 계약·테스트·하네스 검사 → PR 리뷰
- 미결 항목: `service-spec.md`의 `결정 대기`에 남기며 구현에서 임의로 확정하지 않는다.
- 하네스 기준: [`docs/harness/harness-v1.2.md`](../harness/harness-v1.2.md)
- 구현용 요약: [`service-spec.md`](service-spec.md)

기획 문서의 범위·경계·상세 조건·결정 대기 항목이 사라지거나 제목이 바뀌면 하네스가 실패한다. 문서 내용이 바뀌는 PR은 관련 계약, 수용 기준, 미실행 검사를 함께 기록한다.

## 유지 규칙

1. 협업 문서에서 합의된 변경을 이 파일에 옮긴다.
2. API·상태·오류·데이터 구조의 변경은 `packages/contracts/`를 함께 수정한다.
3. 백엔드 책임이나 실행 조건의 변경은 `docs/architecture/backend/` 문서와 하네스 기준을 함께 검토한다.
4. 미확정 내용은 `결정 대기` 또는 결정 기록으로 남기고 확정된 것처럼 작성하지 않는다.
