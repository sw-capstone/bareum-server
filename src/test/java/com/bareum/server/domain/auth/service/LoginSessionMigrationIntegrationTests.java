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
