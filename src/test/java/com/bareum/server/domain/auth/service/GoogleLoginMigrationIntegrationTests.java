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
class GoogleLoginMigrationIntegrationTests {
	@Autowired
	private DataSource dataSource;
	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void upgradesDevelopSchemaWithoutChangingExistingMembersOrSessions() {
		String schema = "google_migration_" + UUID.randomUUID().toString().replace("-", "");
		try {
			Flyway.configure()
					.dataSource(dataSource)
					.schemas(schema)
					.defaultSchema(schema)
					.locations("classpath:db/migration")
					.target("2026.11.04.20.42")
					.outOfOrder(false)
					.cleanDisabled(true)
					.load()
					.migrate();
			long member = jdbc.queryForObject("INSERT INTO " + schema + ".member"
					+ " (email,name,signup_method) VALUES ('fixture@example.invalid','fixture','LOCAL') RETURNING id",
					Long.class);
			UUID family = UUID.randomUUID();
			long session = jdbc.queryForObject("INSERT INTO " + schema + ".refresh_token"
					+ " (member_id,jti,expires_at) VALUES (?,?,now()+interval '8 hours') RETURNING id",
					Long.class, member, family);
			jdbc.update("INSERT INTO " + schema + ".used_refresh_token"
					+ " (token_hash,session_id,family_id,consumed_at,expires_at)"
					+ " VALUES (?,?,?,now(),now()+interval '8 hours')", "a".repeat(64), session, family);
			var beforeMember = jdbc.queryForMap("SELECT * FROM " + schema + ".member WHERE id=?", member);
			var beforeSession = jdbc.queryForMap("SELECT * FROM " + schema + ".refresh_token WHERE id=?", session);
			var beforeUsed =
					jdbc.queryForMap("SELECT * FROM " + schema + ".used_refresh_token WHERE session_id=?", session);

			var upgrade = Flyway.configure()
					.dataSource(dataSource)
					.schemas(schema)
					.defaultSchema(schema)
					.locations("classpath:db/migration")
					.target("2026.11.09.15.20")
					.outOfOrder(false)
					.cleanDisabled(true)
					.load();
			assertEquals(1, upgrade.migrate().migrationsExecuted);
			assertEquals("2026.11.09.15.20", upgrade.info().current().getVersion().toString());
			upgrade.validate();
			assertEquals(0, upgrade.migrate().migrationsExecuted);
			assertEquals(beforeMember, jdbc.queryForMap("SELECT * FROM " + schema + ".member WHERE id=?", member));
			assertEquals(beforeSession,
					jdbc.queryForMap("SELECT * FROM " + schema + ".refresh_token WHERE id=?", session));
			assertEquals(beforeUsed,
					jdbc.queryForMap("SELECT * FROM " + schema + ".used_refresh_token WHERE session_id=?", session));
			assertEquals(0,
					jdbc.queryForObject("SELECT count(*) FROM " + schema + ".google_signup_proof", Integer.class));
		} finally {
			jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		}
	}
}
