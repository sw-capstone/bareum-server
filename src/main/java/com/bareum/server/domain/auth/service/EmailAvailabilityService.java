package com.bareum.server.domain.auth.service;

import com.bareum.server.domain.auth.dto.response.EmailAvailabilityResponse;
import com.bareum.server.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmailAvailabilityService {

    private final MemberRepository memberRepository;

    public EmailAvailabilityResponse checkAvailability(String email) {
        boolean exists = memberRepository.existsByEmailIgnoreCase(email);
        return EmailAvailabilityResponse.of(!exists);
    }
}
