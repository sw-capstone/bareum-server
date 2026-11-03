#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.14,<3.15"
# dependencies = ["anyio>=4,<5", "fastapi>=0.115", "httpx2[http2,brotli,zstd]>=2.13.1,<3", "pydantic>=2.13,<3", "uvicorn[standard]>=0.30"]
# ///
# How to run: scripts/test-pdf-api.sh <bareum-ai repository> <corpus documents.json>
# bareum-ai의 실행 환경을 재사용하며, 원본 다운로드만 로컬 파일로 대체한다.
from __future__ import annotations

import os
import socket
import sys
from datetime import UTC, datetime
from hashlib import sha256
from pathlib import Path
from typing import ClassVar, Final

import anyio
import httpx2
import uvicorn
from bareum_ai.main import create_app
from bareum_ai.pdf_parsing.api.requests import PdfParseRequest, PdfSource
from bareum_ai.pdf_parsing.errors import ErrorAction, ErrorCode
from bareum_ai.pdf_parsing.runtime import PdfParsingRuntime
from bareum_ai.pdf_parsing.types import (
    ContractModel,
    JsonObject,
    ParseAttemptId,
    ReportId,
)
from pydantic import (
    BaseModel,
    ConfigDict,
    SecretStr,
    TypeAdapter,
    field_serializer,
)

REPRESENTATIVE_IDS: Final = (
    "doc_645bfd07e6ac41ec",
    "doc_48c14cb84e2fea23",
    "doc_5366ca993a75bb46",
)
HOST: Final = "example-bucket.s3.ap-northeast-2.amazonaws.com"


class CorpusRecord(BaseModel):
    """검증할 보고서의 식별자와 로컬 PDF 경로를 담는다."""

    model_config: ClassVar[ConfigDict] = ConfigDict(frozen=True)
    document_id: str
    pdf_path: Path


class FixtureRequest(PdfParseRequest):
    """테스트 API가 원본을 읽을 수 있도록 예제 다운로드 URL을 요청 JSON에 포함한다."""

    @field_serializer("source")
    def serialize_fixture_source(self, value: PdfSource) -> JsonObject:
        return {
            "objectKey": value.object_key,
            "originalFilename": value.original_filename,
            "sizeBytes": value.size_bytes,
            "sha256": value.sha256,
            "downloadUrl": value.download_url.get_secret_value(),
            "urlExpiresAt": value.url_expires_at.isoformat(),
        }


class ApiCase(ContractModel):
    """실제 API에 보낼 요청과 해당 요청에서 기대하는 검증 결과를 담는다."""

    name: str
    request: FixtureRequest
    expected_error: ErrorCode | None = None
    expected_action: ErrorAction | None = None
    require_merged_cell: bool = False


def main() -> None:
    manifest, output = Path(sys.argv[1]), Path(sys.argv[2])
    records = TypeAdapter(tuple[CorpusRecord, ...]).validate_json(manifest.read_bytes())
    paths = {record.document_id: record.pdf_path for record in records}
    sources: dict[str, Path] = {}
    cases: list[ApiCase] = []
    for position, identity in enumerate(REPRESENTATIVE_IDS):
        path = paths[identity]
        content = path.read_bytes()
        key = f"reports/contract/{identity}.pdf"
        sources[f"/{key}"] = path
        request = FixtureRequest(
            report_id=ReportId(101 + position),
            parse_attempt_id=ParseAttemptId(201 + position),
            source=PdfSource(
                object_key=key,
                original_filename=path.name,
                size_bytes=len(content),
                sha256=sha256(content).hexdigest(),
                download_url=SecretStr(f"https://{HOST}/{key}?signature=example"),
                url_expires_at=datetime(2099, 1, 1, tzinfo=UTC),
            ),
        )
        # 셀 겹침이 남은 문서는 실패 응답을 검사한다. 정상 변환은 파서 수정 후 재검증한다.
        overlapping_table = identity == "doc_48c14cb84e2fea23"
        cases.append(
            ApiCase(
                name=identity,
                request=request,
                expected_error=ErrorCode.PARSER_FAILED if overlapping_table else None,
                expected_action=ErrorAction.STOP if overlapping_table else None,
                require_merged_cell=position == 0,
            )
        )
    initial = cases[0].request
    cases.append(
        ApiCase(
            name="expired-source",
            expected_error=ErrorCode.SOURCE_URL_EXPIRED,
            expected_action=ErrorAction.CREATE_NEW_ATTEMPT,
            request=initial.model_copy(
                update={
                    "source": initial.source.model_copy(
                        update={"url_expires_at": datetime(2000, 1, 1, tzinfo=UTC)}
                    )
                }
            ),
        )
    )
    cases.append(
        ApiCase(
            name="different-source",
            expected_error=ErrorCode.SOURCE_IDENTITY_MISMATCH,
            expected_action=ErrorAction.STOP,
            request=initial.model_copy(
                update={
                    "source": initial.source.model_copy(update={"sha256": "0" * 64})
                }
            ),
        )
    )
    corrupt = output / "corrupt.pdf"
    _ = corrupt.write_bytes(b"%PDF-1.7\ninvalid fixture")
    sources["/reports/contract/corrupt.pdf"] = corrupt
    cases.append(
        ApiCase(
            name="corrupt-pdf",
            expected_error=ErrorCode.PDF_CORRUPTED,
            expected_action=ErrorAction.STOP,
            request=initial.model_copy(
                update={
                    "source": initial.source.model_copy(
                        update={
                            "object_key": "reports/contract/corrupt.pdf",
                            "download_url": SecretStr(
                                f"https://{HOST}/reports/contract/corrupt.pdf?signature=example"
                            ),
                            "size_bytes": corrupt.stat().st_size,
                            "sha256": sha256(corrupt.read_bytes()).hexdigest(),
                        }
                    )
                }
            ),
        )
    )
    _ = (output / "cases.json").write_bytes(
        TypeAdapter(list[ApiCase]).dump_json(cases, by_alias=True)
    )

    async def source_response(request: httpx2.Request) -> httpx2.Response:
        path = sources.get(request.url.path)
        if path is None:
            return httpx2.Response(404)
        content = await anyio.Path(path).read_bytes()
        return httpx2.Response(
            200, content=content, headers={"Content-Length": str(len(content))}
        )

    def source_client() -> httpx2.AsyncClient:
        return httpx2.AsyncClient(
            transport=httpx2.MockTransport(source_response), follow_redirects=False
        )

    os.environ["BAREUM_AI_INTERNAL_API_TOKEN"] = "example-internal-token"
    os.environ["BAREUM_AI_S3_ALLOWED_HOST"] = HOST
    os.environ["BAREUM_AI_S3_ALLOWED_KEY_PREFIX"] = "reports/"
    application = create_app(PdfParsingRuntime(source_client))
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        address = TypeAdapter(tuple[str, int]).validate_python(listener.getsockname())
        _ = (output / "url").write_text(
            f"http://127.0.0.1:{address[1]}", encoding="utf-8"
        )
        server = uvicorn.Server(
            uvicorn.Config(application, log_level="warning", access_log=False)
        )
        server.run(sockets=[listener])


if __name__ == "__main__":
    main()
