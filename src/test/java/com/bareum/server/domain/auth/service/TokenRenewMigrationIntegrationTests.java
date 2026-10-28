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
class TokenRenewMigrationIntegrationTests {
	@Autowired
	private DataSource dataSource;
	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void upgradesLoginSchemaInOrderWithoutChangingExistingSession() {
		String schema = "renew_migration_" + UUID.randomUUID().toString().replace("-", "");
		try {
			Flyway.configure()
					.dataSource(dataSource)
					.schemas(schema)
					.defaultSchema(schema)
					.locations("classpath:db/migration")
					.target("2026.10.13.09.00")
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
			var before = jdbc.queryForMap("SELECT * FROM " + schema + ".refresh_token WHERE id=?", session);

			var upgrade = Flyway.configure()
					.dataSource(dataSource)
					.schemas(schema)
					.defaultSchema(schema)
					.locations("classpath:db/migration")
					.outOfOrder(false)
					.cleanDisabled(true)
					.load();
			assertEquals(1, upgrade.migrate().migrationsExecuted);
			assertEquals("2026.10.28.16.38", upgrade.info().current().getVersion().toString());
			upgrade.validate();
			assertEquals(0, upgrade.migrate().migrationsExecuted);
			assertEquals(before,
					jdbc.queryForMap("SELECT * FROM " + schema + ".refresh_token WHERE id=?", session));
			assertEquals(0,
					jdbc.queryForObject("SELECT count(*) FROM " + schema + ".used_refresh_token", Integer.class));
			assertEquals(0,
					jdbc.queryForObject("SELECT count(*) FROM " + schema + ".token_renew_window", Integer.class));
		} finally {
			jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		}
	}
}
