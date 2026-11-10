package com.bareum.server.domain.member.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.bareum.server.domain.member.entity.MemberAuth;
import com.bareum.server.domain.member.enums.OAuthProvider;

public interface MemberAuthRepository extends JpaRepository<MemberAuth, Long> {
	@Query("select a.member.id from MemberAuth a where a.provider = :provider and a.providerUserId = :subject")
	Optional<Long> findMemberId(OAuthProvider provider, String subject);

	boolean existsByMember_IdAndProviderAndProviderUserIdAndUnlinkedAtIsNull(
		long memberId,
		OAuthProvider provider,
		String subject
	);
}
