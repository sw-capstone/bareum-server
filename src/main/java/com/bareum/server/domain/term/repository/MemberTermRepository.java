package com.bareum.server.domain.term.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bareum.server.domain.term.entity.MemberTerm;

public interface MemberTermRepository extends JpaRepository<MemberTerm, Long> {
}
