package com.bareum.server.domain.term.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bareum.server.domain.term.dto.response.TermResponse;
import com.bareum.server.domain.term.service.TermService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/terms")
public class TermController implements TermControllerDocs {
	private final TermService termService;

	@GetMapping
	public ResponseEntity<List<TermResponse>> getTerms() {
		return ResponseEntity.ok(termService.getTerms());
	}
}
