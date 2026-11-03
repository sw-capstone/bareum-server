package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class LoginSessionMigrationIntegrationTests {
	@Autowired
	private DataSource dataSource;
	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void upgradesWithoutDeletingLegacyDataAndPreservesRevokedFamilies() {
		String schema = "login_migration_" + UUID.randomUUID().toString().replace("-", "");
		try {
			migration(schema, "2026.10.13.09.00").migrate();
			long member = jdbc.queryForObject("INSERT INTO " + schema + ".member"
					+ " (email,name,signup_method) VALUES ('fixture@example.invalid','fixture','LOCAL') RETURNING id",
					Long.class);
			UUID previous = UUID.randomUUID();
			UUID current = UUID.randomUUID();
			jdbc.update("INSERT INTO " + schema + ".refresh_token(member_id,jti,expires_at,revoked_at)"
					+ " VALUES (?,?,now()+interval '8 hours',now())", member, current);
			jdbc.update(
					"INSERT INTO " + schema
							+ ".login_access_token(token_hash,member_id,family_id,expires_at,revoked_at,created_at)"
							+ " VALUES (?,?,?,now()+interval '15 minutes',now(),now())",
					"a".repeat(64), member, previous);
			jdbc.update("INSERT INTO " + schema
					+ ".login_access_token(token_hash,member_id,family_id,expires_at,created_at)"
					+ " VALUES (?,?,?,now()+interval '15 minutes',now())", "b".repeat(64), member, current);
			var refreshBefore = jdbc.queryForList("SELECT * FROM " + schema + ".refresh_token ORDER BY id");
			var accessBefore = jdbc.queryForList("SELECT * FROM " + schema + ".login_access_token ORDER BY token_hash");
			var upgrade = migration(schema, "2026.10.30.21.48");
			assertEquals(1, upgrade.migrate().migrationsExecuted);
			upgrade.validate();
			assertEquals(0, upgrade.migrate().migrationsExecuted);
			assertEquals(refreshBefore, jdbc.queryForList("SELECT * FROM " + schema + ".refresh_token ORDER BY id"));
			assertEquals(accessBefore,
					jdbc.queryForList("SELECT * FROM " + schema + ".login_access_token ORDER BY token_hash"));
			assertEquals(2,
					jdbc.queryForObject("SELECT count(*) FROM " + schema + ".revoked_login_session", Integer.class));
			assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM " + schema
					+ ".revoked_login_session WHERE family_id=? AND member_id=?", Integer.class, previous, member));
			jdbc.update("UPDATE " + schema + ".refresh_token SET refresh_token_hash=? WHERE member_id=?",
					"c".repeat(64), member);
			assertNull(
					jdbc.queryForObject("SELECT access_token_hash FROM " + schema + ".refresh_token WHERE member_id=?",
							String.class, member));
		} finally {
			jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		}
	}

	@Test
	void removesLegacyAccessStorageOnlyAfterPreservingRefreshAndRevocations() {
		String schema = "login_cleanup_" + UUID.randomUUID().toString().replace("-", "");
		try {
			migration(schema, "2026.10.13.09.00").migrate();
			long member = jdbc.queryForObject("INSERT INTO " + schema + ".member"
					+ " (email,name,signup_method) VALUES ('cleanup@example.invalid','fixture','LOCAL') RETURNING id",
					Long.class);
			UUID previous = UUID.randomUUID(), current = UUID.randomUUID();
			jdbc.update("INSERT INTO " + schema + ".refresh_token"
					+ " (member_id,jti,expires_at,revoked_at,access_token_hash,refresh_token_hash,access_expires_at)"
					+ " VALUES (?,?,now()+interval '8 hours',now(),?,?,now()+interval '15 minutes')",
					member, current, "a".repeat(64), "b".repeat(64));
			jdbc.update("INSERT INTO " + schema + ".login_access_token"
					+ " (token_hash,member_id,family_id,expires_at,revoked_at,created_at)"
					+ " VALUES (?,?,?,now()+interval '15 minutes',now(),now())", "c".repeat(64), member, previous);
			var memberBefore = jdbc.queryForMap("SELECT * FROM " + schema + ".member WHERE id=?", member);
			var refreshBefore =
					jdbc.queryForMap("SELECT * FROM " + schema + ".refresh_token WHERE member_id=?", member);
			refreshBefore.remove("access_token_hash");
			refreshBefore.remove("access_expires_at");
			var transition = migration(schema, "2026.10.30.21.48");
			assertEquals(1, transition.migrate().migrationsExecuted);
			var revokedBefore =
					jdbc.queryForList("SELECT * FROM " + schema + ".revoked_login_session ORDER BY family_id");
			assertEquals(2, revokedBefore.size());
			var cleanup = migration(schema, "2026.11.03.13.35");
			assertEquals(1, cleanup.migrate().migrationsExecuted);
			cleanup.validate();
			assertEquals(0, cleanup.migrate().migrationsExecuted);
			assertEquals(memberBefore, jdbc.queryForMap("SELECT * FROM " + schema + ".member WHERE id=?", member));
			assertEquals(refreshBefore,
					jdbc.queryForMap("SELECT * FROM " + schema + ".refresh_token WHERE member_id=?", member));
			assertEquals(revokedBefore,
					jdbc.queryForList("SELECT * FROM " + schema + ".revoked_login_session ORDER BY family_id"));
			assertNull(jdbc.queryForObject("SELECT to_regclass(?)", String.class, schema + ".login_access_token"));
			assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=?"
					+ " AND table_name='refresh_token' AND column_name IN ('access_token_hash','access_expires_at')",
					Integer.class, schema));
			assertEquals(0, jdbc.queryForObject(
					"SELECT count(*) FROM pg_indexes WHERE schemaname=? AND indexname='refresh_token_access_hash_key'",
					Integer.class, schema));
			jdbc.update("UPDATE " + schema + ".refresh_token SET refresh_token_hash=? WHERE member_id=?",
					"d".repeat(64), member);
			assertEquals("d".repeat(64),
					jdbc.queryForObject("SELECT refresh_token_hash FROM " + schema + ".refresh_token WHERE member_id=?",
							String.class, member));
		} finally {
			jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		}
	}

	private Flyway migration(String schema, String target) {
		return Flyway.configure()
				.dataSource(dataSource)
				.schemas(schema)
				.defaultSchema(schema)
				.locations("classpath:db/migration")
				.target(target)
				.outOfOrder(false)
				.cleanDisabled(true)
				.load();
	}
}
