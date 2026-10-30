package com.bareum.server.domain.auth.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.auth.entity.RevokedLoginSession;
import com.bareum.server.domain.member.enums.MemberStatus;
import com.bareum.server.global.security.LoginPrincipal;

@Transactional(readOnly = true)
public interface LoginSessionQueryRepository extends Repository<RevokedLoginSession, UUID> {
	@Query("""
			select new com.bareum.server.global.security.LoginPrincipal(m.id)
			from Member m
			where m.id = :memberId and m.status = :status and m.withdrawnAt is null
			    and not exists (select s.familyId from RevokedLoginSession s
			        where s.member = m and s.familyId = :family and s.expiresAt > :now)
			    and not exists (select r.id from RefreshToken r
			        where r.member = m and r.jti = :family and r.revokedAt is not null)
			""")
	Optional<LoginPrincipal> findActivePrincipal(
		@Param("memberId") long memberId,
		@Param("family") UUID family,
		@Param("now") Instant now,
		@Param("status") MemberStatus status
	);

	@Modifying
	@Transactional
	@Query("delete from RevokedLoginSession s where s.expiresAt <= :now")
	int deleteExpired(@Param("now") Instant now);
}
