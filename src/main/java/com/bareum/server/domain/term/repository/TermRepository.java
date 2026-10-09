package com.bareum.server.domain.term.repository;

import com.bareum.server.domain.term.entity.Term;
import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface TermRepository extends JpaRepository<Term, Long> {

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select t from Term t order by t.id")
    List<Term> findAllForSignup();
}
