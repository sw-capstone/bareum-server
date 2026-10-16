package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class EmailVerificationRequestLockTests {

    private static final String EMAIL = "Lock-Test@Example.Invalid";

    // 접두사 + DB 대문자 변환 이메일의 SHA-256에서 구한 테스트 잠금 키.
    // 비밀키나 발송 제한 수치가 아닙니다.
    private static final long EXPECTED_LOCK_KEY = 2608156036078042327L;

    @Autowired
    private EmailVerificationRequestLock requestLock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Test
    void callingWithoutTransactionIsRejected() {
        assertThrows(
                IllegalTransactionStateException.class,
                () -> requestLock.lockForEmail(EMAIL)
        );
    }

    @Test
    void lockIsHeldUntilCommitAndReleasedAfterCommit() throws SQLException {
        verifyLockLifecycle(false);
    }

    @Test
    void lockIsReleasedAfterRollback() throws SQLException {
        verifyLockLifecycle(true);
    }

    private void verifyLockLifecycle(boolean rollback) throws SQLException {
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        // 직접 얻은 별도 연결로 다른 트랜잭션에서 잠금을 확인합니다.
        try (Connection observer = dataSource.getConnection()) {
            observer.setAutoCommit(false);

            try {
                transaction.executeWithoutResult(status -> {
                    requestLock.lockForEmail(EMAIL);

                    try {
                        assertFalse(
                                tryAcquireLock(observer),
                                "원래 트랜잭션이 진행 중이면 같은 잠금을 얻을 수 없어야 합니다."
                        );
                    } catch (SQLException exception) {
                        throw new IllegalStateException(
                                "다른 DB 연결에서 잠금을 확인하지 못했습니다.",
                                exception
                        );
                    }

                    if (rollback) {
                        status.setRollbackOnly();
                    }
                });

                assertTrue(
                        tryAcquireLock(observer),
                        "원래 트랜잭션 종료 후에는 잠금을 얻을 수 있어야 합니다."
                );
            } finally {
                // 검증 연결에서 획득한 잠금도 해제합니다.
                observer.rollback();
            }
        }
    }

    private boolean tryAcquireLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_try_advisory_xact_lock(?)"
        )) {
            statement.setLong(1, EXPECTED_LOCK_KEY);

            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }
}
