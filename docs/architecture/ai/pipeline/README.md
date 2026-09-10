# Pipeline Orchestration

파서부터 후검사까지의 순서와 실패 전파를 정의한다. 실제 호출은 각 하위 영역의 계약을 통해 연결한다.

선행 단계가 실패하거나 실행되지 않으면 후속 단계는 성공으로 표시하지 않고 `SKIPPED`, `PARTIAL`, `FAILED`, `WITHHELD` 중 실제 상태를 기록한다.
