package com.bareum.server.domain.auth.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.bareum.server.domain.auth.entity.RefreshToken;

import jakarta.persistence.LockModeType;

public interface LogoutRepository extends Repository<RefreshToken, Long> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<RefreshToken> findByMemberIdAndJti(long memberId, UUID jti);

	@Query(value = """
			select exists (
			    select 1 from used_refresh_token
			    where token_hash = :hash and session_id = :sessionId and family_id = :family
			)
			""", nativeQuery = true)
	boolean wasConsumed(String hash, long sessionId, UUID family);

	@Modifying
	@Query("""
			update RefreshToken r set r.revokedAt = :now, r.updatedAt = :now
			where r.id = :sessionId and r.jti = :family and r.revokedAt is null
			""")
	void revoke(long sessionId, UUID family, Instant now);
}
