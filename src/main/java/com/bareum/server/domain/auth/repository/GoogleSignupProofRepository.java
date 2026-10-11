package com.bareum.server.domain.auth.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class GoogleSignupProofRepository {
    private final JdbcTemplate jdbc;

    public GoogleSignupProofRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> subject(String hash) {
        return jdbc
                .query(
                        "SELECT google_subject FROM google_signup_proof WHERE token_hash=?",
                        (rs, n) -> rs.getString(1),
                        hash)
                .stream()
                .findFirst();
    }

    public Optional<Proof> lock(String hash) {
        return jdbc
                .query(
                        "SELECT google_subject,email,name,expires_at,consumed_at FROM"
                            + " google_signup_proof WHERE token_hash=? FOR UPDATE",
                        (rs, n) ->
                                new Proof(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getTimestamp(4).toInstant(),
                                        rs.getTimestamp(5) == null
                                                ? null
                                                : rs.getTimestamp(5).toInstant()),
                        hash)
                .stream()
                .findFirst();
    }

    public boolean consume(String hash, Instant now) {
        return jdbc.update(
                        "UPDATE google_signup_proof SET consumed_at=? WHERE token_hash=? AND"
                            + " consumed_at IS NULL AND expires_at>?",
                        Timestamp.from(now),
                        hash,
                        Timestamp.from(now))
                == 1;
    }

    public record Proof(
            String subject, String email, String name, Instant expiresAt, Instant consumedAt) {
        @Override
        public String toString() {
            return "GoogleSignupProof[redacted]";
        }
    }
}
