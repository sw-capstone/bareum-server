package com.bareum.server.domain.term.controller;

import com.bareum.server.domain.term.dto.response.TermResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;

@Tag(name = "Term", description = "가입 시 동의할 약관 조회")
public interface TermControllerDocs {

    @Operation(
            summary = "약관 목록 조회",
            description = """
			약관의 식별자·종류·버전·필수 여부를 조회합니다.
			MVP에서는 종류별 초기 버전 하나를 사용합니다.
			약관 원문은 프론트에서 관리하며, 저장된 약관이 없으면 빈 배열을 반환합니다.
			"""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "약관 목록 조회 성공",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(
                                    schema = @Schema(implementation = TermResponse.class)
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "서버 오류로 약관 목록 조회 실패",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail"),
                            examples = @ExampleObject(
                                    ref = "#/components/examples/InternalServerError"
                            )
                    )
            )
    })
    ResponseEntity<List<TermResponse>> getTerms();
}
