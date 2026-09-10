# AI Worker 실행 단위

AI Worker 실행 단위의 위치다. PDF 파싱, 임베딩, 검색과 모델 추론을 이 디렉터리에 둔다.

구성 요소는 `pyproject.toml`, `Dockerfile`, Worker 소스와 테스트다. 모델·큐·CUDA·컨테이너 설정은 Worker 구현과 함께 관리한다.
