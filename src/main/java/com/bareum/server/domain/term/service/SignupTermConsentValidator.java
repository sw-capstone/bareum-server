package com.bareum.server.domain.term.service;

import com.bareum.server.domain.term.config.SignupTermProperties;
import com.bareum.server.domain.term.dto.request.SignupAgreementsRequest;
import com.bareum.server.domain.term.entity.Term;
import com.bareum.server.domain.term.enums.TermCode;
import com.bareum.server.domain.term.exception.TermErrorCode;
import com.bareum.server.domain.term.exception.TermException;
import com.bareum.server.domain.term.repository.TermRepository;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SignupTermConsentValidator {

    private final TermRepository termRepository;
    private final SignupTermProperties properties;

    @Transactional(propagation = Propagation.MANDATORY)
    public List<ValidatedConsent> validate(SignupAgreementsRequest request) {
        if (request == null) {
            throw new TermException(TermErrorCode.INVALID_CONSENT_REQUEST);
        }
        for (TermCode code : TermCode.values()) {
            if (request.agreed(code) == null) {
                throw new TermException(TermErrorCode.INVALID_CONSENT_REQUEST);
            }
        }
        Map<TermCode, Term> byCode = new EnumMap<>(TermCode.class);
        for (Term term : termRepository.findAllForSignup()) {
            if (byCode.putIfAbsent(term.getCode(), term) != null) {
                throw new TermException(TermErrorCode.SIGNUP_TERMS_NOT_CONFIGURED);
            }
        }
        List<ValidatedConsent> selected = new ArrayList<>();
        for (TermCode code : TermCode.values()) {
            Term term = byCode.get(code);
            boolean required = code != TermCode.MARKETING_CONSENT;
            if (term == null || term.isRequired() != required
                    || !properties.versionFor(code).equals(term.getVersion())) {
                throw new TermException(TermErrorCode.SIGNUP_TERMS_NOT_CONFIGURED);
            }
            boolean agreed = request.agreed(code);
            if (required && !agreed) {
                throw new TermException(TermErrorCode.REQUIRED_TERM_NOT_AGREED);
            }
            selected.add(new ValidatedConsent(term, agreed));
        }
        return List.copyOf(selected);
    }

    public record ValidatedConsent(Term term, boolean agreed) {
    }
}
