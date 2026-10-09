package com.bareum.server.domain.term.dto.response;

import com.bareum.server.domain.term.entity.Term;
import com.bareum.server.domain.term.enums.TermCode;

public record TermResponse(
        Long termId,
        TermCode code,
        String version,
        boolean required
) {

    public static TermResponse from(Term term) {
        return new TermResponse(
                term.getId(),
                term.getCode(),
                term.getVersion(),
                term.isRequired()
        );
    }
}
