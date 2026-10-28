package com.bareum.server.domain.auth.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.auth.entity.EmailOutbox;
import com.bareum.server.domain.auth.enums.EmailOutboxState;

@Transactional(readOnly = true)
public interface EmailOutboxQueryRepository extends Repository<EmailOutbox, UUID> {
	@Query("""
			select count(o) > 0 from EmailOutbox o
			where upper(o.email) = upper(:email)
			    and (o.state in :activeStates or o.queuedAt > :since)
			""")
	boolean hasPendingOrRecentDelivery(
		@Param("email") String email,
		@Param("activeStates") Collection<EmailOutboxState> activeStates,
		@Param("since") Instant since
	);

	long countByEmailIgnoreCaseAndQueuedAtGreaterThanEqualAndStateNot(
		String email,
		Instant since,
		EmailOutboxState state
	);

	boolean existsByVerification_IdAndCodeHashAndStateIn(
		Long verificationId,
		String codeHash,
		Collection<EmailOutboxState> states
	);

	// A single projection avoids combining verification and delivery states from different reads.
	@Query("""
			select new com.bareum.server.domain.auth.repository.EmailOutboxDeliverySnapshot(
			    o.id, o.state, v.expiresAt,
			    case when v.consumedAt is not null or v.verifiedAt is not null or v.codeHash <> o.codeHash
			        or exists (select latest.id from EmailVerification latest
			            where upper(latest.email) = upper(o.email) and latest.purpose = o.purpose
			                and latest.id > v.id)
			        or exists (select newer.id from EmailOutbox newer
			            where upper(newer.email) = upper(o.email) and newer.verification = v
			                         and newer.id <> o.id and newer.queuedAt >= o.queuedAt)
			        then true else false end)
			from EmailOutbox o join o.verification v
			where o.id = :id
			""")
	Optional<EmailOutboxDeliverySnapshot> findDelivery(@Param("id") UUID id);
}
