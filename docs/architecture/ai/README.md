# AI Pipeline

문서 입력을 파싱하고, RMA 라우팅·결정론 규칙·근거 검색·판정·수정안·후검사·Trace로 연결한다.

```text
parsers → rma → rules/checks → retrieval → judgment → revisions → trace
```

AI 영역은 공개 API나 화면을 소유하지 않는다. 영역 간 형식은 `packages/contracts/`에서 관리한다. 근거가 없거나 후검사를 통과하지 못한 결과는 확정 판정으로 반환하지 않는다.
