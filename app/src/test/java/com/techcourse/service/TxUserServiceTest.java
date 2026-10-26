package com.techcourse.service;

import com.interface21.dao.DataAccessException;
import com.interface21.jdbc.CannotGetJdbcConnectionException;
import com.interface21.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class TxUserServiceTest {

    private DataSource dataSource;
    private Connection connection;
    private UserService appUserService;
    private UserService txUserService;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        appUserService = mock(UserService.class);

        when(dataSource.getConnection()).thenReturn(connection);

        txUserService = new TxUserService(appUserService, dataSource);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.unbindResource(dataSource);
    }

    @Test
    void keepsBusinessFailureWhenRollbackAndReleaseFail() throws SQLException {
        final var businessFailure = new DataAccessException("비즈니스 실패");
        final var rollbackFailure = new SQLException("rollback failed");

        doThrow(businessFailure)
                .when(appUserService).changePassword(1L, "newPassword", "gugu");
        doThrow(rollbackFailure).when(connection).rollback();
        doThrow(new SQLException("close failed")).when(connection).close();

        final var actual = assertThrows(
                DataAccessException.class,
                () -> txUserService.changePassword(1L, "newPassword", "gugu")
        );

        assertThat(actual).isSameAs(businessFailure);
        assertThat(actual.getSuppressed()).hasSize(2);
        assertThat(actual.getSuppressed()[0]).isSameAs(rollbackFailure);
        assertThat(actual.getSuppressed()[1])
                .isInstanceOf(CannotGetJdbcConnectionException.class)
                .hasRootCauseMessage("close failed");

        assertThat(TransactionSynchronizationManager.getResource(dataSource)).isNull();
    }

    @Test
    void throwsReleaseFailureWhenBusinessSucceeds() throws SQLException {
        doThrow(new SQLException("close failed")).when(connection).close();

        final var actual = assertThrows(
                CannotGetJdbcConnectionException.class,
                () -> txUserService.changePassword(1L, "newPassword", "gugu")
        );

        assertThat(actual).hasRootCauseMessage("close failed");
        verify(connection).commit();
        verify(connection, never()).rollback();

        assertThat(TransactionSynchronizationManager.getResource(dataSource)).isNull();
    }

    @Test
    void keepsErrorWhenReleaseFails() throws SQLException {
        final var error = new AssertionError("의도적인 Error");

        doThrow(error)
                .when(appUserService).changePassword(1L, "newPassword", "gugu");
        doThrow(new SQLException("close failed")).when(connection).close();

        final var actual = assertThrows(
                AssertionError.class,
                () -> txUserService.changePassword(1L, "newPassword", "gugu")
        );

        assertThat(actual).isSameAs(error);
        assertThat(actual.getSuppressed())
                .singleElement()
                .isInstanceOf(CannotGetJdbcConnectionException.class);
        verify(connection).rollback();

        assertThat(TransactionSynchronizationManager.getResource(dataSource)).isNull();
    }
}
