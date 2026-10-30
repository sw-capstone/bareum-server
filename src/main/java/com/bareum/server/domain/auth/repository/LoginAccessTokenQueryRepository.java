package com.bareum.server.domain.auth.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.auth.entity.LoginAccessToken;
import com.bareum.server.domain.member.enums.MemberStatus;
import com.bareum.server.global.security.LoginPrincipal;

@Transactional(readOnly = true)
public interface LoginAccessTokenQueryRepository extends Repository<LoginAccessToken, String> {

	@Modifying
	@Transactional
	@Query("delete from LoginAccessToken a where a.expiresAt <= :now")
	int deleteExpired(@Param("now") Instant now);

	@Query("""
			select new com.bareum.server.global.security.LoginPrincipal(m.id)
			from LoginAccessToken a join a.member m
			where a.tokenHash = :hash and m.id = :memberId and a.familyId = :family
			    and a.expiresAt > :now and a.revokedAt is null
			    and m.status = :status and m.withdrawnAt is null
			    and not exists (select r.id from RefreshToken r
			        where r.member = m and r.jti = a.familyId and r.revokedAt is not null)
			""")
	Optional<LoginPrincipal> findActivePrincipal(
		@Param("hash") String hash,
		@Param("memberId") long memberId,
		@Param("family") UUID family,
		@Param("now") Instant now,
		@Param("status") MemberStatus status
	);
}
