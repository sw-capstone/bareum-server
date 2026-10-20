package com.bareum.server.domain.term.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bareum.server.domain.term.entity.Term;

public interface TermRepository extends JpaRepository<Term, Long> {
}
