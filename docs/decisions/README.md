# 결정 기록

결정 파일에는 날짜·상태·소유 역할, 쟁점, 선택지, 결정, 이유, 영향과 재검토 조건을 기록한다. 개인 이름 대신 역할명을 사용하고 실제 검토 책임을 기록한다.

## 결정 목록

| ID | 상태 | 내용 |
|---|---|---|
| [`DEC-REPO-001`](DEC-REPO-001-repository-strategy.md) | 결정 | 프론트엔드·백엔드·AI 세 저장소 구성 방식 |
| [`DEC-ID-001`](DEC-ID-001-id-governance.md) | 결정 | 프로젝트 ID 형식·발급·상태·연결 규칙 |
| [`DEC-RULE-001`](DEC-RULE-001-rule-catalog-authority.md) | 검토 필요 | 최신 규칙 카탈로그를 제품 규칙 공식 원천으로 지정하는 안 |
| [`DEC-SEC-001`](DEC-SEC-001-secret-detection.md) | 결정 | Gitleaks 도입과 민감정보 검사 차단·경고 기준 |

## 구현 협의 초안

- [Auth·Term 팀 협의 안건](auth-term-meeting-review.md): 가입·인증 복구와 실제 연동·배포를 막는 네 안건.

- [이메일 로그인·공통 인증](login-authentication-draft.md): AUTH-002 구현, 임시 설정과 프론트 연동 계약.

- [로그인 토큰 갱신](token-renew-draft.md): AUTH-009·CSRF 연동, 토큰 교체·재사용·최대 만료 기준.

- [로그아웃](logout-draft.md): AUTH-003·현재 로그인 폐기·쿠키 삭제·갱신 경쟁 처리.

- [Google 로그인](google-login-draft.md): AUTH-004·Google 인증 검증·신규 가입 증명.

- [Google 가입 완료](google-signup-draft.md): AUTH-011·약관 동의·증명 소비·가입 후 자동 로그인.

- [이메일 인증 재발송 Outbox 전환](email-resend-outbox-draft.md): AUTH-007·202 접수·상태 조회·가입 연동.

- [비밀번호 재설정](password-reset-draft.md): AUTH-010·증명 소비·비밀번호 변경·기존 세션 폐기 초안.

- [Auth·Term 명세·리뷰 기준 정비](auth-term-review-alignment.md): 기존 정책·리뷰 대조, 재설정 발송 대상 검사와 미확정 경계.
