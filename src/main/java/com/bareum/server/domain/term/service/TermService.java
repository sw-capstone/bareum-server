package com.bareum.server.domain.term.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.term.dto.response.TermResponse;
import com.bareum.server.domain.term.repository.TermRepository;

import lombok.RequiredArgsConstructor;

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
