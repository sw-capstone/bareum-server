package com.bareum.server.domain.term.service;

import com.bareum.server.domain.term.dto.response.TermResponse;
import com.bareum.server.domain.term.repository.TermRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TermService {

    private final TermRepository termRepository;

    public List<TermResponse> getTerms() {
        return termRepository.findAll()
                .stream()
                .map(TermResponse::from)
                .toList();
    }
}
