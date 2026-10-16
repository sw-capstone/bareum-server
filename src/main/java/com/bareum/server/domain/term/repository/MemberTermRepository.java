package com.bareum.server.domain.term.repository;

import com.bareum.server.domain.term.entity.MemberTerm;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberTermRepository extends JpaRepository<MemberTerm, Long> {
}
