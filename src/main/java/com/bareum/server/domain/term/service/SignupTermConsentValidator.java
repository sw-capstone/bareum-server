package com.bareum.server.domain.term.service;

import com.bareum.server.domain.term.dto.request.TermConsentRequest;
import com.bareum.server.domain.term.entity.Term;
import com.bareum.server.domain.term.enums.TermCode;
import com.bareum.server.domain.term.exception.TermErrorCode;
import com.bareum.server.domain.term.exception.TermException;
import com.bareum.server.domain.term.repository.TermRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SignupTermConsentValidator {

    private final TermRepository termRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Term> validate(List<TermConsentRequest> requests) {
        List<Term> catalog = termRepository.findAllForSignup();
        Map<Long, Term> byId = new HashMap<>();
        Map<TermCode, Term> byCode = new HashMap<>();
        for (Term term : catalog) {
            if (byCode.putIfAbsent(term.getCode(), term) != null) {
                throw new TermException(TermErrorCode.SIGNUP_TERMS_NOT_CONFIGURED);
            }
            byId.put(term.getId(), term);
        }
        for (TermCode code : Set.of(TermCode.TERMS_OF_SERVICE, TermCode.PRIVACY_POLICY)) {
            Term term = byCode.get(code);
            if (term == null || !term.isRequired()) {
                throw new TermException(TermErrorCode.SIGNUP_TERMS_NOT_CONFIGURED);
            }
        }
        if (requests == null) {
            throw new TermException(TermErrorCode.INVALID_CONSENT_REQUEST);
        }

        Set<Long> seenIds = new HashSet<>();
        Set<Long> agreedIds = new HashSet<>();
        List<Term> selected = new ArrayList<>();
        for (TermConsentRequest request : requests) {
            if (request == null || request.termId() == null || request.agreed() == null
                    || !seenIds.add(request.termId())) {
                throw new TermException(TermErrorCode.INVALID_CONSENT_REQUEST);
            }
            Term term = byId.get(request.termId());
            if (term == null || !term.getVersion().equals(request.version())) {
                throw new TermException(TermErrorCode.INVALID_CONSENT_REQUEST);
            }
            if (request.agreed()) {
                agreedIds.add(request.termId());
            }
            selected.add(term);
        }
        if (catalog.stream().anyMatch(term -> term.isRequired() && !agreedIds.contains(term.getId()))) {
            throw new TermException(TermErrorCode.REQUIRED_TERM_NOT_AGREED);
        }
        return List.copyOf(selected);
    }
}
