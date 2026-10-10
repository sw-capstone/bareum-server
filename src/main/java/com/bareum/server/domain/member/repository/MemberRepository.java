package com.bareum.server.domain.member.repository;

import com.bareum.server.domain.member.entity.Member;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {

    // Include withdrawn records so callers can inspect the complete email history.
    List<Member> findAllByEmailIgnoreCase(String email);

    // Completed withdrawals no longer reserve the address; all other states do.
    default boolean existsByEmailIgnoreCase(String email) {
        return findAllByEmailIgnoreCase(email).stream()
                .anyMatch(member -> !member.isWithdrawalCompleted());
    }

    // Historical withdrawn records must never become the reset/login target.
    default Optional<Member> findByEmailIgnoreCase(String email) {
        return findAllByEmailIgnoreCase(email).stream()
                .filter(member -> !member.isWithdrawalCompleted())
                .findFirst();
    }
}
