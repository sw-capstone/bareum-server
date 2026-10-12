package com.bareum.server.domain.member.repository;

import static org.junit.jupiter.api.Assertions.*;

import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.service.EmailAvailabilityService;
import com.bareum.server.domain.auth.service.EmailVerificationAccountValidator;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class MemberEmailUniquenessIntegrationTests {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private MemberRepository members;
    @Autowired private EmailAvailabilityService availability;
    @Autowired private EmailVerificationAccountValidator accounts;

    private final List<String> addresses = new ArrayList<>();
    private final List<String> schemas = new ArrayList<>();

    private String address() {
        String email = "member-" + UUID.randomUUID() + "@example.invalid";
        addresses.add(email);
        return email;
    }

    private void insert(String table, String email, String method, String status, boolean withdrawn) {
        jdbc.update("INSERT INTO " + table + " (email, name, signup_method, password_hash, status, withdrawn_at) "
                        + "VALUES (?, 'test member', ?, 'test-hash', ?, " + (withdrawn ? "now()" : "NULL") + ")",
                email, method, status);
    }

    private void assertDuplicate(Runnable action) {
        var error = assertThrows(DataIntegrityViolationException.class, action::run);
        assertInstanceOf(SQLException.class, error.getMostSpecificCause());
        assertEquals("23505", ((SQLException) error.getMostSpecificCause()).getSQLState());
        assertTrue(error.getMostSpecificCause().getMessage().contains("member_email_key"));
    }

    @AfterEach
    void cleanup() {
        for (String email : addresses) {
            jdbc.update("DELETE FROM member WHERE upper(email) = upper(?)", email);
        }
        // Only UUID-named schemas created by this test are removed.
        for (String schema : schemas) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void databaseRejectsCaseVariantLocalAccounts() {
        String email = address();
        insert("member", email, "LOCAL", "ACTIVE", false);
        assertDuplicate(() -> insert("member", email.toUpperCase(Locale.ROOT), "LOCAL", "ACTIVE", false));
    }

    @Test
    void databaseRejectsLocalGoogleCollisionInBothOrders() {
        String local = address();
        insert("member", local, "LOCAL", "ACTIVE", false);
        assertDuplicate(() -> insert("member", local.toUpperCase(Locale.ROOT), "GOOGLE", "ACTIVE", false));
        String google = address();
        insert("member", google, "GOOGLE", "ACTIVE", false);
        assertDuplicate(() -> insert("member", google.toUpperCase(Locale.ROOT), "LOCAL", "ACTIVE", false));
    }

    @Test
    void databaseRejectsUpdatesToAnotherAccountsCaseVariant() {
        String first = address();
        String second = address();
        insert("member", first, "LOCAL", "ACTIVE", false);
        insert("member", second, "GOOGLE", "ACTIVE", false);
        assertDuplicate(() -> jdbc.update("UPDATE member SET email = ? WHERE email = ?",
                first.toUpperCase(Locale.ROOT), second));
    }

    @Test
    void withdrawalIsInspectedAndAllowsImmediateReuseWithoutChangingHistory() {
        String email = address();
        insert("member", email, "LOCAL", "DELETED", true);
        insert("member", email.toUpperCase(Locale.ROOT), "GOOGLE", "DELETED", true);
        assertEquals(2, members.findAllByEmailIgnoreCase(email).size());
        assertTrue(members.findAllByEmailIgnoreCase(email).stream().allMatch(m -> m.isWithdrawalCompleted()));
        assertFalse(members.existsByEmailIgnoreCase(email));
        assertTrue(availability.checkAvailability(email).available());
        assertDoesNotThrow(() -> accounts.checkCanSend(email, VerificationPurpose.SIGNUP));
        assertThrows(AuthException.class, () -> accounts.checkCanSend(email, VerificationPurpose.PASSWORD_RESET));

        // Even the exact historical spelling can be stored again, with no waiting period.
        insert("member", email, "LOCAL", "ACTIVE", false);
        assertEquals(3, members.findAllByEmailIgnoreCase(email).size());
        assertTrue(members.existsByEmailIgnoreCase(email.toUpperCase(Locale.ROOT)));
        assertFalse(availability.checkAvailability(email).available());
        assertEquals("ACTIVE", members.findByEmailIgnoreCase(email).orElseThrow().getStatus().name());
        assertEquals(email, accounts.resolveRecipient(email.toUpperCase(Locale.ROOT), VerificationPurpose.PASSWORD_RESET));
        assertThrows(AuthException.class, () -> accounts.checkCanSend(email, VerificationPurpose.SIGNUP));
        assertDuplicate(() -> insert("member", email.toUpperCase(Locale.ROOT), "GOOGLE", "ACTIVE", false));
    }

    @Test
    void suspendedAndIncompleteWithdrawalsStillReserveEmail() {
        String[] states = {"SUSPENDED", "DELETED", "ACTIVE"};
        boolean[] timestamps = {false, false, true};
        for (int i = 0; i < states.length; i++) {
            String email = address();
            insert("member", email, "LOCAL", states[i], timestamps[i]);
            assertTrue(members.existsByEmailIgnoreCase(email));
            assertFalse(availability.checkAvailability(email).available());
            assertThrows(AuthException.class, () -> accounts.checkCanSend(email, VerificationPurpose.SIGNUP));
            assertDuplicate(() -> insert("member", email.toUpperCase(Locale.ROOT), "LOCAL", "ACTIVE", false));
        }
    }

    @Test
    void withdrawnAccountCannotBeReactivatedIfEmailHasBeenReused() {
        String email = address();
        insert("member", email, "LOCAL", "DELETED", true);
        insert("member", email.toUpperCase(Locale.ROOT), "GOOGLE", "ACTIVE", false);
        assertDuplicate(() -> jdbc.update("UPDATE member SET status = 'ACTIVE', withdrawn_at = NULL WHERE email = ?", email));
    }

    @Test
    void differentEmailsRemainAvailableAndStoredSpellingIsPreserved() {
        String first = address();
        String second = address();
        insert("member", first.toUpperCase(Locale.ROOT), "LOCAL", "ACTIVE", false);
        insert("member", second, "GOOGLE", "ACTIVE", false);
        assertEquals(first.toUpperCase(Locale.ROOT), members.findByEmailIgnoreCase(first).orElseThrow().getEmail());
        assertTrue(members.existsByEmailIgnoreCase(second.toUpperCase(Locale.ROOT)));
    }

    private String oldSchema() {
        String schema = "member_email_test_" + UUID.randomUUID().toString().replace("-", "");
        schemas.add(schema);
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("2026.10.04.05.35").load().migrate();
        return schema;
    }

    private Flyway upgrade(String schema) {
        return Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load();
    }

    @Test
    void upgradeStopsOnExistingCaseDuplicatesWithoutChangingRowsOrOldConstraint() {
        String schema = oldSchema();
        String email = address();
        insert(schema + ".member", email, "LOCAL", "ACTIVE", false);
        insert(schema + ".member", email.toUpperCase(Locale.ROOT), "GOOGLE", "ACTIVE", false);
        assertThrows(FlywayException.class, () -> upgrade(schema).migrate());
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".member", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace "
                + "WHERE n.nspname = ? AND c.conname = 'member_email_key'", Long.class, schema));
    }

    @Test
    void upgradePreservesWithdrawnHistoryAndEnforcesNewIndex() {
        String schema = oldSchema();
        String email = address();
        insert(schema + ".member", email, "LOCAL", "DELETED", true);
        insert(schema + ".member", email.toUpperCase(Locale.ROOT), "GOOGLE", "ACTIVE", false);
        upgrade(schema).migrate();
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".member", Long.class));
        assertDuplicate(() -> insert(schema + ".member", email, "LOCAL", "ACTIVE", false));
        insert(schema + ".member", email, "LOCAL", "DELETED", true);
    }
}
