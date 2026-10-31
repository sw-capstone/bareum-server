package com.bareum.server.domain.member.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bareum.server.domain.member.entity.Member;

import jakarta.persistence.LockModeType;

public interface MemberRepository extends JpaRepository<Member, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from Member m where m.id = :id")
	Optional<Member> findByIdForUpdate(@Param("id") long id);

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
