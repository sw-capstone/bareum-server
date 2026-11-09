package com.bareum.server.domain.member.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bareum.server.domain.member.entity.MemberAuth;
import com.bareum.server.domain.member.enums.OAuthProvider;

import jakarta.persistence.LockModeType;

public interface MemberAuthRepository extends JpaRepository<MemberAuth, Long> {
	@Query("select a.member.id from MemberAuth a where a.provider = :provider and a.providerUserId = :subject")
	Optional<Long> findMemberId(OAuthProvider provider, String subject);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from MemberAuth a where a.member.id = :memberId and a.provider = :provider and a.providerUserId = :subject")
	Optional<MemberAuth> findForUpdate(
		@Param("memberId") long memberId,
		@Param("provider") OAuthProvider provider,
		@Param("subject") String subject
	);
}
